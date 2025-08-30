(() => {
  const params = new URLSearchParams(location.search);
  const roomId = params.get('roomId');
  if (!roomId) { alert('Missing roomId'); location.href='/'; return; }

  const name = prompt('Your name? (shown to others)', 'Guest') || 'Guest';
  const roomBadge = document.getElementById('roomBadge');
  const meName = document.getElementById('meName');
  roomBadge.textContent = roomId;
  meName.textContent = name;

  const video = document.getElementById('video');
  const fileInput = document.getElementById('fileInput');
  const uploadBtn = document.getElementById('uploadBtn');
  const currentFile = document.getElementById('currentFile');
  const inviteBtn = document.getElementById('inviteBtn');
  const membersDiv = document.getElementById('members');
  const chatLog = document.getElementById('chatLog');
  const chatInput = document.getElementById('chatInput');
  const sendChat = document.getElementById('sendChat');

  // WebSocket connection
  const wsProtocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const ws = new WebSocket(`${wsProtocol}//${location.host}/ws?roomId=${encodeURIComponent(roomId)}&name=${encodeURIComponent(name)}`);

  let myId = null;
  let suppress = false; // prevent feedback loops

  // Members list
  const members = new Map(); // clientId -> name

  // WebRTC for screen share
  let isBroadcaster = false;
  let displayStream = null;
  const peers = new Map(); // clientId -> RTCPeerConnection
  const iceServers = [{ urls: 'stun:stun.l.google.com:19302' }];

  function logChat(text) {
    const p = document.createElement('div');
    p.textContent = text;
    chatLog.appendChild(p);
    chatLog.scrollTop = chatLog.scrollHeight;
  }

  ws.onmessage = async (e) => {
    const msg = JSON.parse(e.data);
    if (msg.type === 'client-id') {
      myId = msg.clientId;
    } else if (msg.type === 'setSource') {
      if (isBroadcaster) return; // ignore while sharing screen
      currentFile.textContent = msg.fileName || '(unnamed)';
      if (video.src !== msg.url) {
        suppress = true;
        video.src = msg.url;
        // wait for metadata then do nothing else
        video.addEventListener('loadedmetadata', () => { suppress = false; }, { once:true });
      }
    } else if (msg.type === 'control') {
      if (isBroadcaster) return; // ignore while sharing
      const action = msg.action;
      const t = msg.time ?? null;
      try {
        suppress = true;
        if (t != null && Math.abs(video.currentTime - t) > 0.3) video.currentTime = t;
        if (action === 'play') {
          await video.play();
        } else if (action === 'pause') {
          video.pause();
        } else if (action === 'seek') {
          video.currentTime = t || 0;
        }
      } finally {
        setTimeout(() => suppress = false, 50);
      }
    } else if (msg.type === 'chat') {
      logChat(`${msg.name || 'Anon'}: ${msg.text}`);
    } else if (msg.type === 'member-joined') {
      members.set(msg.clientId, msg.name || 'Guest');
      renderMembers();
      // if I am broadcaster, start connection with this new member
      if (isBroadcaster && displayStream) {
        startConnectionTo(msg.clientId);
      }
    } else if (msg.type === 'member-left') {
      peers.get(msg.clientId)?.close();
      peers.delete(msg.clientId);
      members.delete(msg.clientId);
      renderMembers();
    } else if (msg.type === 'member-list') {
      // initial population
      msg.members.forEach(m => { members.set(m.clientId, m.name); });
      renderMembers();
    } else if (msg.type === 'webrtc-offer') {
      // I'm a viewer, receive offer from broadcaster
      const from = msg.from;
      let pc = peers.get(from);
      if (!pc) pc = createPeer(from, false);
      await pc.setRemoteDescription(new RTCSessionDescription(msg.sdp));
      const answer = await pc.createAnswer();
      await pc.setLocalDescription(answer);
      ws.send(JSON.stringify({ type:'webrtc-answer', to: from, from: myId, sdp: pc.localDescription }));
    } else if (msg.type === 'webrtc-answer') {
      const from = msg.from;
      const pc = peers.get(from);
      if (pc) await pc.setRemoteDescription(new RTCSessionDescription(msg.sdp));
    } else if (msg.type === 'webrtc-ice') {
      const from = msg.from;
      const pc = peers.get(from);
      if (pc && msg.candidate) {
        try { await pc.addIceCandidate(new RTCIceCandidate(msg.candidate)); } catch {}
      }
    }
  };

  function renderMembers() {
    membersDiv.innerHTML = '';
    members.forEach((nm, id) => {
      const div = document.createElement('div');
      div.className = 'member';
      div.textContent = id === myId ? nm + ' (you)' : nm;
      membersDiv.appendChild(div);
    });
  }

  // Invite link
  inviteBtn.addEventListener('click', async () => {
    const url = location.origin + '/room.html?roomId=' + encodeURIComponent(roomId);
    try {
      await navigator.clipboard.writeText(url);
      inviteBtn.textContent = 'Link copied!';
      setTimeout(() => inviteBtn.textContent = 'Copy Invite Link', 1500);
    } catch {
      prompt('Copy room link:', url);
    }
  });

  // Upload
  uploadBtn.addEventListener('click', async () => {
    const file = fileInput.files?.[0];
    if (!file) { alert('Choose a file'); return; }
    const fd = new FormData();
    fd.append('file', file);
    const res = await fetch(`/api/rooms/${encodeURIComponent(roomId)}/upload`, { method:'POST', body: fd });
    const data = await res.json();
    if (data.error) { alert(data.error); return; }
    currentFile.textContent = data.fileName;
    video.src = data.url;
    ws.send(JSON.stringify({ type:'setSource', url: data.url, fileName: data.fileName }));
  });

  // Sync playback
  video.addEventListener('play', () => {
    if (suppress) return;
    ws.send(JSON.stringify({ type:'control', action:'play', time: video.currentTime }));
  });
  video.addEventListener('pause', () => {
    if (suppress) return;
    ws.send(JSON.stringify({ type:'control', action:'pause', time: video.currentTime }));
  });
  video.addEventListener('seeked', () => {
    if (suppress) return;
    ws.send(JSON.stringify({ type:'control', action:'seek', time: video.currentTime }));
  });

  // Chat
  sendChat.addEventListener('click', () => {
    const text = chatInput.value.trim();
    if (!text) return;
    ws.send(JSON.stringify({ type:'chat', text, name }));
    chatInput.value = '';
  });
  chatInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') sendChat.click(); });

  // Screen share
  const startShare = document.getElementById('startShare');
  const stopShare = document.getElementById('stopShare');

  startShare.addEventListener('click', async () => {
    try {
      displayStream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: true });
      isBroadcaster = true;
      // replace video source with local stream for the sharer
      video.srcObject = displayStream;
      ws.send(JSON.stringify({ type:'introduce-broadcaster', from: myId }));
      // start to all existing members (except me)
      members.forEach((_, id) => {
        if (id !== myId) startConnectionTo(id);
      });
      displayStream.getVideoTracks()[0].addEventListener('ended', stopBroadcast);
    } catch (e) {
      alert('Screen share failed: ' + e.message);
    }
  });

  stopShare.addEventListener('click', stopBroadcast);

  function stopBroadcast() {
    isBroadcaster = false;
    if (displayStream) {
      displayStream.getTracks().forEach(t => t.stop());
      displayStream = null;
    }
    video.srcObject = null;
    peers.forEach((pc, id) => pc.close());
    peers.clear();
    ws.send(JSON.stringify({ type:'end-broadcast', from: myId }));
  }

  function createPeer(remoteId, asBroadcaster) {
    const pc = new RTCPeerConnection({ iceServers });
    pc.onicecandidate = (ev) => {
      if (ev.candidate) {
        ws.send(JSON.stringify({ type:'webrtc-ice', to: remoteId, from: myId, candidate: ev.candidate }));
      }
    };
    pc.ontrack = (ev) => {
      // viewer side: show remote stream
      if (!isBroadcaster) {
        video.srcObject = ev.streams[0];
      }
    };
    if (asBroadcaster && displayStream) {
      displayStream.getTracks().forEach(track => pc.addTrack(track, displayStream));
    }
    pc.onconnectionstatechange = () => {
      if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected' || pc.connectionState === 'closed') {
        pc.close();
        peers.delete(remoteId);
      }
    };
    peers.set(remoteId, pc);
    return pc;
  }

  async function startConnectionTo(remoteId) {
    const pc = createPeer(remoteId, true);
    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    ws.send(JSON.stringify({ type:'webrtc-offer', to: remoteId, from: myId, sdp: pc.localDescription }));
  }

  // On load, fetch current room info to set existing video source (if any)
  (async () => {
    const res = await fetch('/api/rooms/' + encodeURIComponent(roomId));
    const data = await res.json();
    if (data.currentVideoUrl) {
      currentFile.textContent = data.fileName || '(unnamed)';
      video.src = data.currentVideoUrl;
    }
  })();
})();
