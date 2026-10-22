import { favoriteItem, homeResumes, resumeItems, catalogItem } from './nas-model.js';
const $ = id => document.getElementById(id);
const TOKEN_KEY = 'jojotv_companion_token';
const typeCode = { movies: 'MOVIE', series: 'SERIES', live: 'LIVE' };
const keyName = value => String(value || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').trim().toLocaleLowerCase('fr');
const option = (value, text) => { const node = document.createElement('option'); node.value = value; node.textContent = text; return node; };

export class NasLibrary {
  constructor(onChange) {
    this.onChange = onChange; this.overview = null; this.items = []; this.favorites = []; this.catalog = [];
    this.providers = []; this.groups = []; this.activeType = 'movies'; this.nextPage = null; this.revision = 0;
    this.artwork = new Map(); this.pendingArtwork = new Map(); this.imageQueue = []; this.imageRequests = 0;
    new ResizeObserver(entries => document.documentElement.style.setProperty('--nas-height', entries[0].target.getBoundingClientRect().height+'px')).observe(document.querySelector('.nas-connection'));
    this.imageObserver = new IntersectionObserver(entries => {
      for (const entry of entries) if (entry.isIntersecting) { this.imageObserver.unobserve(entry.target); this.queueArtwork(entry.target); }
    }, { rootMargin: '180px' });
    $('nas-login').addEventListener('submit', event => this.login(event));
    $('nas-connect').addEventListener('click', () => this.openAccounts());
    $('nas-refresh').addEventListener('click', () => this.refresh());
    $('nas-logout').addEventListener('click', () => this.logout());
    $('provider-select').addEventListener('change', () => this.loadCategory(this.activeType));
    $('group-select').addEventListener('change', () => this.loadPage(0));
    $('catalog-more').addEventListener('click', () => this.loadPage(this.nextPage));
    $('nas-tv').addEventListener('change', () => { sessionStorage.setItem('jojohome_web_tv', $('nas-tv').value); this.refreshProviders(); });
    $('companion-button').addEventListener('click', () => this.openManagement());
    $('manage-accounts').addEventListener('click', () => this.openManagement('profiles'));
    $('manage-providers').addEventListener('click', () => this.openManagement('iptv'));
    $('companion-dialog').addEventListener('close', () => { $('companion-frame').removeAttribute('src'); this.refresh(); });
  }
  get token() { return sessionStorage.getItem(TOKEN_KEY) || ''; }
  get connected() { return Boolean(this.overview && this.token); }
  account() { return this.connected ? this.accounts.find(item => item.id === this.activeAccount) || this.accounts[0] : null; }
  media() { return [...new Map([...this.items, ...this.catalog].map(item => [item.id, item])).values()]; }
  async api(path, options = {}) {
    const response = await fetch(path, { ...options, credentials: 'omit', headers: { 'Content-Type': 'application/json', ...(this.token ? { Authorization: 'Bearer ' + this.token } : {}), ...options.headers } });
    const data = await response.json();
    if (!response.ok) { const error = new Error(data.error || 'La requête NAS a échoué.'); error.status = response.status; throw error; }
    return data;
  }
  setStatus(text) { $('nas-status').textContent = text; }
  openAccounts() { this.renderAccounts(); if (!$('accounts-dialog').open) $('accounts-dialog').showModal(); }
  async login(event) {
    event.preventDefault(); const button = $('nas-login-button'); button.disabled = true; $('nas-login-status').textContent = 'Connexion au NAS…';
    try {
      const result = await this.api('/api/sync/auth/login', { method: 'POST', body: JSON.stringify({ email: $('nas-email').value.trim(), password: $('nas-password').value }) });
      sessionStorage.setItem(TOKEN_KEY, result.token); $('nas-password').value = '';
      await this.refresh(); if (this.connected) { $('accounts-dialog').close(); this.openAccounts(); }
    } catch (error) { $('nas-login-status').textContent = error.message; }
    finally { $('nas-password').value = ''; button.disabled = false; }
  }
  logout() {
    sessionStorage.removeItem(TOKEN_KEY); this.overview = null; this.items = []; this.catalog = []; this.favorites = [];
    this.revision++; this.accountRevision = (this.accountRevision || 0)+1; this.clearArtwork(); $('profile-list').replaceChildren(); this.renderAccounts(); this.renderConnection(); this.onChange();
  }
  renderConnection() {
    const connected = this.connected;
    $('nas-connect').hidden = connected; $('nas-refresh').hidden = !connected; $('nas-logout').hidden = !connected;
    $('nas-tv-row').hidden = !connected; $('nas-login').hidden = connected;
    $('local-account-section').hidden = connected; $('manage-accounts').hidden = !connected;
    $('catalog-controls').hidden = !connected;
    if (!connected) { this.setStatus('Connectez votre compte NAS pour retrouver vos comptes, fournisseurs et favoris.'); $('nas-account-email').textContent = ''; $('nas-provider-count').textContent = ''; }
  }
  async refresh() {
    if (!this.token) { this.overview = null; this.renderConnection(); this.onChange(); return; }
    $('nas-refresh').disabled = true;
    try {
      const data = await this.api('/api/companion/overview'); this.overview = data;
      this.accounts = data.accounts?.length ? data.accounts : (data.profiles || []).map(item => ({ ...item, id: String(item.profileId), syncProfile: item.profileId }));
      this.activeAccount = this.accounts.some(item => item.id === this.activeAccount) ? this.activeAccount : sessionStorage.getItem('jojohome_web_account');
      if (!this.accounts.some(item => item.id === this.activeAccount)) this.activeAccount = this.accounts[0]?.id;
      const selectedTv = sessionStorage.getItem('jojohome_web_tv') || $('nas-tv').value || data.devices?.[0]?.ip || '';
      $('nas-tv').replaceChildren(option('', 'Choisir une TV'), ...(data.devices || []).map(device => option(device.ip, device.name)));
      if (selectedTv && ![...$('nas-tv').options].some(item => item.value === selectedTv)) $('nas-tv').append(option(selectedTv, selectedTv));
      $('nas-tv').value = selectedTv; this.renderConnection(); this.renderAccounts();
      await this.selectAccount(this.activeAccount);
      await this.refreshProviders();
    } catch (error) {
      if (error.status === 401) { sessionStorage.removeItem(TOKEN_KEY); this.overview = null; this.items = []; this.catalog = []; this.clearArtwork(); this.renderConnection(); this.onChange(); }
      this.setStatus(error.message); $('nas-login-status').textContent = error.message;
    } finally { $('nas-refresh').disabled = false; }
  }
  async selectAccount(id) {
    const revision = this.accountRevision = (this.accountRevision || 0)+1; this.revision++; this.activeAccount = id; this.catalog = []; this.items = []; this.favorites = []; this.clearArtwork();
    sessionStorage.setItem('jojohome_web_account', id || ''); this.onChange(); this.renderAccounts();
    const account = this.account(); if (!account) { this.setStatus('Aucun compte JojoHome synchronisé. Ouvrez Gestion NAS pour vérifier les comptes.'); return; }
    this.setStatus('Chargement des favoris de ' + account.name + '…');
    try {
      const endpoint = account.syncProfile ? '/api/sync/profiles/' + account.syncProfile + '/iptv-favorites' : '/api/companion/accounts/' + encodeURIComponent(account.id) + '/favorites';
      const result = await this.api(endpoint); if (revision !== this.accountRevision || !this.connected) return;
      this.favorites = (result.favorites || []).map(item => favoriteItem(item, account)); this.items = this.favorites;
      const profiles = (this.overview.profiles || []).filter(item => keyName(item.name) === keyName(account.name));
      const profileId = account.syncProfile || (profiles.length === 1 ? profiles[0].profileId : null);
      let resumeUnavailable = false;
      if (profileId) {
        try {
          const [progress, home, watched] = await Promise.all([this.api('/api/sync/profiles/' + profileId + '/watch-progress'), this.api('/api/sync/profiles/1/blobs/jojo-home-settings'), this.api('/api/sync/profiles/' + profileId + '/watched-items')]);
          if (revision !== this.accountRevision || !this.connected) return;
          const retained = homeResumes(progress, home.value, profileId, watched);
          this.items = resumeItems(retained, this.favorites, account);
        } catch { resumeUnavailable = true; }
      }
      this.setStatus(account.name + ' · ' + this.favorites.length + (this.favorites.length === 1 ? ' favori synchronisé' : ' favoris synchronisés') + ' · ' + (this.overview.providers || []).length + ' fournisseurs' + (resumeUnavailable ? ' · Reprises temporairement indisponibles' : ''));
      this.onChange();
    } catch (error) { if (revision === this.accountRevision) this.setStatus(error.message); }
  }
  renderAccounts() {
    this.renderConnection(); if (!this.connected) return;
    const buttons = (this.accounts || []).map(account => {
      const button = document.createElement('button'); button.className = 'profile-option'; button.setAttribute('aria-pressed', String(account.id === this.activeAccount));
      const avatar = document.createElement('span'); avatar.className = 'profile-avatar'; avatar.textContent = account.avatar?.emoji || account.name.slice(0,1).toUpperCase(); avatar.style.borderColor = account.ornamentColor || '#d3a033';
      if (account.avatar?.image && /^[a-z0-9_-]+\.png$/.test(account.avatar.image)) { const image = document.createElement('img'); image.src = '/api/ui-avatars/' + encodeURIComponent(account.avatar.image); image.alt = ''; avatar.replaceChildren(image); }
      const label = document.createElement('span'); label.textContent = account.name;
      const count = document.createElement('small'); count.textContent = (account.iptvFavorites || 0) + ' favoris'; label.append(count); button.append(avatar, label);
      button.addEventListener('click', async () => { $('accounts-dialog').close(); await this.selectAccount(account.id); this.onChange('home'); }); return button;
    });
    $('profile-list').replaceChildren(...buttons); $('nas-account-email').textContent = this.overview.account.email + ' · NAS connecté';
  }
  async refreshProviders() {
    if (!this.connected) return;
    const selected = $('provider-select').value, tv = $('nas-tv').value, generation = this.providerRevision = (this.providerRevision || 0)+1;
    this.providers = (this.overview.providers || []).map(item => ({ ...item, origin: 'tv' }));
    const results = await Promise.allSettled([
      this.api('/api/player/providers'),
      tv ? this.api('/api/tv/providers?' + new URLSearchParams({ tv, port: '8093' })) : Promise.resolve(null)
    ]);
    if (!this.connected || tv !== $('nas-tv').value || generation !== this.providerRevision) return;
    if (results[0].status === 'fulfilled') this.providers.push(...(results[0].value.providers || []).map(item => ({ ...item, origin: 'nas' })));
    if (results[1].status === 'fulfilled' && results[1].value && !results[1].value.message) {
      this.providers = [...results[1].value.providers.map(item => ({ ...item, origin: 'tv' })), ...this.providers.filter(item => item.origin === 'nas')];
    }
    $('provider-select').replaceChildren(option('', 'Favoris et reprises du compte'), ...this.providers.map(item => option(item.origin+':'+item.id, item.name + ' · ' + (item.origin === 'tv' ? 'JojoHome' : 'NAS'))));
    if ([...$('provider-select').options].some(item => item.value === selected)) $('provider-select').value = selected;
    $('nas-provider-count').textContent = this.providers.length + ' sources disponibles · M3U, Xtream et Stalker';
    if (results[1].status === 'fulfilled' && results[1].value?.message) $('nas-provider-count').textContent += ' · TV indisponible, favoris NAS conservés';
    if ($('provider-select').value) await this.loadCategory(this.activeType);
  }
  selectedProvider() { return this.providers.find(item => item.origin+':'+item.id === $('provider-select').value); }
  searchCatalog(query) {
    const provider = this.selectedProvider();
    if (provider?.origin !== 'nas' || !this.providerItems) return this.catalog;
    this.searchItems = this.providerItems.filter(item => (item.title || item.name || '').toLocaleLowerCase('fr').includes(query)).slice(0,80).map(item => catalogItem(item, provider, this.activeType, this.favorites));
    return this.searchItems;
  }
  async loadCategory(type) {
    this.activeType = type; this.catalog = []; this.searchItems = []; this.providerItems = null; this.nextPage = null; this.groups = []; $('catalog-more').hidden = true; $('group-control').hidden = true; $('catalog-status').textContent = '';
    const provider = this.selectedProvider(); this.onChange();
    if (!this.connected || !provider || !typeCode[type]) return;
    const revision = ++this.revision;
    $('catalog-status').textContent = 'Chargement du catalogue…';
    try {
      if (provider.origin === 'tv') {
        if (!$('nas-tv').value) throw new Error('Choisissez la TV où JojoHome est actif.');
        const data = await this.api(this.catalogURL(provider)); if (revision !== this.revision) return;
        this.groups = (data.groups || []).filter(group => group.visible !== false);
        $('group-select').replaceChildren(...this.groups.map(group => option(group.id, group.name))); $('group-control').hidden = !this.groups.length;
        if (!this.groups.length) { $('catalog-status').textContent = 'Aucun groupe visible pour ce type de contenu.'; return; }
      }
      await this.loadPage(0);
    } catch (error) { if (revision === this.revision) $('catalog-status').textContent = error.message; }
  }
  catalogURL(provider, page = 0, groupId = '') {
    return '/api/tv/catalog?' + new URLSearchParams({ tv: $('nas-tv').value, port: '8093', sourceId: provider.id, type: typeCode[this.activeType], groupId, page });
  }
  async loadPage(page) {
    const provider = this.selectedProvider(); if (!provider || page === null) return;
    const revision = ++this.revision, type = this.activeType;
    $('catalog-more').disabled = true; $('catalog-status').textContent = 'Chargement des titres…';
    if (page === 0) { this.catalog = []; this.onChange(); }
    try {
      let data;
      if (provider.origin === 'tv') data = await this.api(this.catalogURL(provider, page, $('group-select').value));
      else {
        if (page === 0) this.providerItems = (await this.api('/api/player/items?' + new URLSearchParams({ provider: provider.id, section: type }))).items || [];
        data = { items: this.providerItems.slice(page*80, (page+1)*80), nextPage: (page+1)*80 < this.providerItems.length ? page+1 : null };
      }
      if (revision !== this.revision) return;
      this.catalog.push(...(data.items || []).filter(item => item.visible !== false).map(item => catalogItem(item, provider, type, this.favorites)));
      this.nextPage = data.nextPage ?? null; $('catalog-more').hidden = this.nextPage === null;
      $('catalog-status').textContent = this.catalog.length + ' titres chargés' + (provider.origin === 'nas' ? ' sur ' + this.providerItems.length : '') + ' · ' + provider.name;
      this.onChange();
    } catch (error) { if (revision === this.revision) $('catalog-status').textContent = error.message; }
    finally { if (revision === this.revision) $('catalog-more').disabled = false; }
  }
  openManagement(page = 'profiles') {
    $('accounts-dialog').close(); $('settings-dialog').close();
    $('companion-frame').src = './companion/#' + page; $('companion-dialog').showModal();
  }
  async episodes(item) {
    if (item.nasFavorite) return (await this.api(item.playbackBase + '/episodes')).episodes || [];
    if (item.catalogProvider?.origin === 'nas') return ((await this.api('/api/player/episodes?' + new URLSearchParams({ provider: item.catalogProvider.id, series: item.catalogId }))).items || []).map(entry => ({ ...catalogItem(entry, item.catalogProvider, 'series', []), isEpisode: true }));
    return [];
  }
  canPlay(item) { return item.nasFavorite || item.catalogProvider?.origin === 'nas'; }
  async playback(item, episode) {
    if (item.nasFavorite) return this.api(item.playbackBase + '/playback', { method: 'POST', body: JSON.stringify({ ...(episode ? { episode } : {}), position: item.positionSeconds || 0 }) });
    return { url: '/api/player/stream?' + new URLSearchParams({ provider: item.catalogProvider.id, section: item.type, item: item.catalogId }), format: item.type === 'live' ? 'mpegts' : 'native', live: item.type === 'live' };
  }
  clearArtwork() { this.imageObserver.disconnect(); this.imageQueue = []; this.pendingArtwork.clear(); for (const url of this.artwork.values()) URL.revokeObjectURL(url); this.artwork.clear(); this.artworkGeneration = (this.artworkGeneration || 0)+1; }
  decorateArtwork(image, item) {
    this.imageObserver.unobserve(image); delete image.dataset.nasArtwork;
    if (!item.artwork?.startsWith('/api/')) return;
    image.dataset.nasArtwork = item.artwork;
    if (this.artwork.has(item.artwork)) image.src = this.artwork.get(item.artwork);
    else this.imageObserver.observe(image);
  }
  queueArtwork(image) {
    this.imageQueue.push({ image, generation: this.artworkGeneration }); this.drainArtwork();
  }
  drainArtwork() {
    while (this.imageRequests < 4 && this.imageQueue.length) {
      const { image, generation } = this.imageQueue.shift(), path = image.dataset.nasArtwork; this.imageRequests++;
      const load = this.artwork.has(path) ? Promise.resolve(this.artwork.get(path)) : this.pendingArtwork.get(path) || fetch(path, { credentials: 'omit', headers: { Authorization: 'Bearer ' + this.token } })
        .then(async response => { if (!response.ok) throw new Error('Vignette indisponible.'); const blob = await response.blob(); if (!blob.type.startsWith('image/')) throw new Error('Vignette invalide.'); return URL.createObjectURL(blob); });
      this.pendingArtwork.set(path, load);
      load.then(url => { if (generation !== this.artworkGeneration) { URL.revokeObjectURL(url); return; } this.artwork.set(path, url); if (image.isConnected && image.dataset.nasArtwork === path) image.src = url; })
        .catch(() => {}).finally(() => {
          if (this.pendingArtwork.get(path) === load) this.pendingArtwork.delete(path); this.imageRequests--; this.drainArtwork();
        });
    }
  }
}
