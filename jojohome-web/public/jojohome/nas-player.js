const libraries = new Map();
function loadLibrary(name) {
  if (!libraries.has(name)) libraries.set(name, new Promise((resolve, reject) => {
    const script = document.createElement('script'); script.src = '/api/companion/player-assets/' + name;
    script.onload = resolve; script.onerror = () => { libraries.delete(name); reject(new Error('Le lecteur NAS ne peut pas être chargé.')); };
    document.head.append(script);
  }));
  return libraries.get(name);
}
export class NasPlayer {
  constructor(video, status, nas) { this.video = video; this.status = status; this.nas = nas; this.generation = 0; }
  async start(item, episode) {
    this.stop(); const generation = this.generation;
    this.status.textContent = 'Préparation de la lecture…';
    try {
      const playback = await this.nas.playback(item, episode);
      if (generation !== this.generation) { if (playback.session) this.nas.api('/api/companion/playback/'+playback.session, { method: 'DELETE' }).catch(() => {}); return; }
      this.session = playback.session;
      if (playback.format === 'hls' && !this.video.canPlayType('application/vnd.apple.mpegurl')) {
        await loadLibrary('hls-1.7.3.min.js'); if (generation !== this.generation) return;
        if (!window.Hls?.isSupported()) throw new Error('Ce navigateur ne prend pas en charge le lecteur HLS.');
        this.engine = new window.Hls(); this.engine.attachMedia(this.video); this.engine.loadSource(playback.url);
        this.engine.on(window.Hls.Events.ERROR, (_, data) => { if (data.fatal) this.status.textContent = 'Le fournisseur a interrompu le flux ou ce format vidéo est incompatible.'; });
      } else if (playback.format === 'mpegts') {
        await loadLibrary('mpegts-1.8.2.min.js'); if (generation !== this.generation) return;
        if (!window.mpegts?.isSupported()) throw new Error('Ce navigateur ne prend pas en charge les flux TV MPEG-TS.');
        window.mpegts.LoggingControl.enableAll = false;
        this.engine = window.mpegts.createPlayer({ type: 'mpegts', isLive: playback.live, url: new URL(playback.url, location.href).href }, { enableWorker: true });
        this.engine.on(window.mpegts.Events.ERROR, () => { this.status.textContent = 'Ce flux TV est interrompu ou incompatible avec le navigateur.'; });
        this.engine.attachMediaElement(this.video); this.engine.load();
      } else this.video.src = playback.url;
      this.status.textContent = '';
      this.video.play().catch(() => { this.status.textContent = 'Appuyez sur lecture pour démarrer la vidéo.'; });
    } catch (error) { if (generation === this.generation) this.status.textContent = error.message; }
  }
  stop() {
    this.generation++; this.engine?.destroy(); this.engine = null;
    if (this.session) this.nas.api('/api/companion/playback/' + this.session, { method: 'DELETE' }).catch(() => {});
    this.session = null;
  }
}
