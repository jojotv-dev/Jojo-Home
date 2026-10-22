import { NasLibrary } from './nas.js';
import { NasPlayer } from './nas-player.js';
const $ = id => document.getElementById(id);
const STORAGE_KEY = 'jojohome_web_v1';
const TYPES = { videos: 'Vidéos', movies: 'Films', series: 'Séries', live: 'TV' };
const DEFAULT_ARTWORK = new URL('./assets/jojo-home-banner.jpeg', location.href).href;
const themes = { gold: ['#d3a033', '#101010'], blue: ['#202f52', '#ffffff'], teal: ['#009999', '#101010'], violet: ['#673ab7', '#ffffff'] };
const localFiles = new Map();
const newId = () => 'web-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 9);
const defaultState = () => ({ profiles: [{ id: 'local', name: 'Moi', media: [] }], activeProfile: 'local', settings: { color: 'gold', heroSize: 'medium' }, apps: [] });
let state;
try { state = JSON.parse(localStorage.getItem(STORAGE_KEY)) || defaultState(); } catch { state = defaultState(); }
if (!Array.isArray(state.profiles) || !state.profiles.length) state = defaultState();
state.settings = { color: 'gold', heroSize: 'medium', ...state.settings };
state.apps ||= [];
let page = 'home', heroIndex = 0, heroItems = [], detailsId = null, playingId = null, playingProfile = null, lastProgressSave = 0, noticeTimer;
const localProfile = () => state.profiles.find(item => item.id === state.activeProfile) || state.profiles[0];
const profile = () => nas.account() || localProfile();
const media = () => nas.connected ? nas.media() : localProfile().media || [];
const element = (tag, className, text) => { const node = document.createElement(tag); if (className) node.className = className; if (text !== undefined) node.textContent = text; return node; };
const nas = new NasLibrary(next => { if (next) showPage(next); else { renderHome(); if (page !== 'home') renderLibrary(); } });
const nasPlayer = new NasPlayer($('player'), $('player-status'), nas);
const findItem = id => media().find(entry => entry.id === id) || nas.searchItems?.find(entry => entry.id === id);
function persist() {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(state)); }
  catch { notice('Le navigateur ne permet pas d’enregistrer vos réglages.'); }
}
function notice(message) {
  $('notice').textContent = message; $('notice').hidden = false;
  clearTimeout(noticeTimer); noticeTimer = setTimeout(() => { $('notice').hidden = true; }, 4500);
}
function safeURL(value) {
  if (!value || typeof value !== 'string') return '';
  try { const url = new URL(value, location.href); return ['http:', 'https:'].includes(url.protocol) ? url.href : ''; } catch { return ''; }
}
function imageSource(item) { return item.nas && item.artwork?.startsWith('/api/') ? nas.artwork.get(item.artwork) || DEFAULT_ARTWORK : safeURL(item.artwork) || DEFAULT_ARTWORK; }
function duration(minutes) {
  const n = Math.round(Number(minutes)); if (!Number.isFinite(n) || n <= 0) return '';
  return n < 60 ? n + ' min' : Math.floor(n / 60) + 'h' + String(n % 60).padStart(2, '0');
}
function metadata(item) { return [item.genre, item.year, duration(item.durationMinutes)].filter(Boolean).join(' · '); }
function icon(file) { const node = element('span', 'icon'); node.style.setProperty('--icon', `url('./assets/${file}')`); node.setAttribute('aria-hidden', 'true'); return node; }
function applySettings() {
  const [accent, text] = themes[state.settings.color] || themes.gold;
  document.documentElement.style.setProperty('--accent', accent);
  document.documentElement.style.setProperty('--accent-text', text);
  document.body.dataset.heroSize = ['small', 'medium', 'large'].includes(state.settings.heroSize) ? state.settings.heroSize : 'medium';
  $('theme-color').value = state.settings.color; $('hero-size').value = state.settings.heroSize;
}
function updateClock() {
  const now = new Date(); $('clock').textContent = now.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' }); $('clock').dateTime = now.toISOString();
  $('date').textContent = now.toLocaleDateString('fr-FR', { weekday: 'short', day: 'numeric', month: 'short' });
}
function showPage(next, moveFocus = true) {
  page = next === 'home' || TYPES[next] ? next : 'home';
  $('home-page').hidden = page !== 'home'; $('library-page').hidden = page === 'home';
  document.querySelectorAll('.header-command[data-page]').forEach(button => {
    const active = button.dataset.page === page; button.classList.toggle('active', active);
    if (active) button.setAttribute('aria-current', 'page'); else button.removeAttribute('aria-current');
  });
  if (page === 'home') renderHome(); else renderLibrary();
  if (page !== 'home') nas.loadCategory(page);
  history.replaceState(null, '', '#'+page);
  window.scrollTo(0, 0);
  if (moveFocus) { const heading = page === 'home' ? $('main') : $('library-title'); heading.tabIndex = -1; heading.focus({ preventScroll: true }); }
}
function renderHome() {
  $('header-avatar').textContent = profile().name.trim().slice(0, 1).toUpperCase() || 'M';
  const account = nas.account();
  if (account?.avatar?.image && /^[a-z0-9_-]+\.png$/.test(account.avatar.image)) { const image = element('img'); image.src = '/api/ui-avatars/' + encodeURIComponent(account.avatar.image); image.alt = ''; $('header-avatar').replaceChildren(image); }
  else if (account?.avatar?.emoji) $('header-avatar').textContent = account.avatar.emoji;
  $('account-button').setAttribute('aria-label', 'Compte · ' + profile().name);
  heroItems = media().filter(item => {
    const total = Number(item.durationMinutes || 0) * 60;
    return item.positionSeconds > 0 && (!total || item.positionSeconds < total - Math.min(30, total * .05));
  });
  heroIndex = Math.min(heroIndex, Math.max(0, heroItems.length - 1));
  $('hero').hidden = !heroItems.length; $('suggestions').hidden = Boolean(heroItems.length);
  if (heroItems.length) renderHero(); else renderSuggestions();
}
function renderHero() {
  const item = heroItems[heroIndex]; if (!item) return;
  $('hero-artwork').src = imageSource(item); $('hero-title').textContent = item.title;
  nas.decorateArtwork($('hero-artwork'), item);
  $('hero-meta').textContent = metadata(item); $('hero-cast').textContent = item.cast ? 'Acteurs : '+item.cast : ''; $('hero-cast').hidden = !item.cast;
  $('hero-episode').textContent = item.episode || ''; $('hero-episode').hidden = !item.episode;
  $('hero-description').textContent = item.description || '';
  $('hero-play').lastElementChild.textContent = playableURL(item) ? 'Reprendre' : 'Voir la fiche';
  const total = Number(item.durationMinutes || 0) * 60;
  $('hero-remaining').textContent = total > item.positionSeconds ? Math.ceil((total-item.positionSeconds)/60)+' min restantes' : '';
  $('hero-progress').hidden = !total; $('hero-progress').value = total ? item.positionSeconds / total : 0;
  const dots = heroItems.map((entry, index) => {
    const dot = element('button'); dot.type = 'button'; dot.setAttribute('aria-label', entry.title); dot.setAttribute('aria-current', String(index === heroIndex));
    dot.addEventListener('click', () => { heroIndex = index; renderHero(); }); return dot;
  });
  $('hero-dots').replaceChildren(...dots); $('hero-previous').hidden = $('hero-next').hidden = heroItems.length < 2;
}
function cycleHero(direction) { if (!heroItems.length) return; heroIndex = (heroIndex + direction + heroItems.length) % heroItems.length; renderHero(); }
function renderSuggestions() {
  const icons = { videos: 'play.svg', movies: 'videos.svg', series: 'series.svg', live: 'tv.svg' };
  const columns = nas.connected ? ['live', 'movies', 'series'] : ['videos', 'movies', 'series'];
  const heading = document.querySelector('.suggestion-headings>span'); heading.replaceChildren(icon(icons[columns[0]]), document.createTextNode(TYPES[columns[0]]));
  const cards = [];
  for (let row = 0; row < 3; row++) for (const type of columns) {
    const item = media().filter(entry => entry.type === type)[row];
    const card = element('button', 'suggestion-card');
    if (item) {
      const image = element('img'); image.src = imageSource(item); image.alt = ''; image.style.cssText = 'width:100%;max-height:85px;object-fit:cover;border-radius:6px';
      card.append(image, element('span', '', item.title)); card.addEventListener('click', () => openDetails(item.id));
      nas.decorateArtwork(image, item);
    } else {
      card.append(icon(icons[type]), element('span', '', nas.connected ? type === 'live' ? 'Explorer les chaînes TV' : 'Explorer les '+TYPES[type].toLowerCase() : row === 0 ? 'Ajouter des '+TYPES[type].toLowerCase() : 'Votre prochaine découverte'));
      card.addEventListener('click', () => showPage(type));
    }
    cards.push(card);
  }
  $('suggestion-grid').replaceChildren(...cards);
}
function renderLibrary() {
  $('library-title').textContent = TYPES[page]; $('library-profile').textContent = profile().name;
  $('add-video').hidden = nas.connected || page === 'live'; $('import-library').hidden = nas.connected;
  $('catalog-controls').hidden = !nas.connected || page === 'videos';
  const query = $('library-search').value.trim().toLocaleLowerCase('fr');
  const favoritesOnly = $('favorites-filter').getAttribute('aria-pressed') === 'true';
  const candidates = nas.connected && nas.selectedProvider() && page !== 'videos' ? query ? nas.searchCatalog(query) : nas.catalog : media();
  const items = candidates.filter(item => item.type === page && (!favoritesOnly || item.favorite) && (!query || [item.title, item.description, item.cast].filter(Boolean).join(' ').toLocaleLowerCase('fr').includes(query)));
  const cards = items.map(item => {
    const card = element('button', 'media-card'); const image = element('img'); image.src = imageSource(item); image.alt = ''; image.loading = 'lazy';
    const copy = element('span', '', item.title); copy.append(element('small', '', metadata(item) || TYPES[item.type]));
    if (item.description) copy.append(element('span', 'card-synopsis', item.description));
    if (item.cast) copy.append(element('small', 'card-cast', 'Acteurs : '+item.cast));
    card.append(image, copy); nas.decorateArtwork(image, item);
    card.setAttribute('aria-label', item.title+(item.favorite ? ' · Favori' : '')); card.addEventListener('click', () => openDetails(item.id)); return card;
  });
  $('media-grid').replaceChildren(...cards); $('empty-library').hidden = Boolean(items.length);
  $('library-count').textContent = items.length + (items.length === 1 ? ' titre' : ' titres');
  $('empty-library').querySelector('p').textContent = nas.connected ? 'Retrouvez les favoris du compte ou choisissez un fournisseur et un groupe. Les catalogues JojoHome nécessitent une TV active.' : 'Choisissez vos vidéos ou importez un catalogue pour retrouver vos titres ici.';
}
function openDialog(id) { if (!$(id).open) $(id).showModal(); }
async function openDetails(id) {
  const item = findItem(id); if (!item) return;
  detailsId = id; $('detail-title').textContent = item.title; $('detail-artwork').src = imageSource(item);
  nas.decorateArtwork($('detail-artwork'), item);
  $('detail-meta').textContent = metadata(item); $('detail-description').textContent = item.description || 'Aucun synopsis disponible.';
  $('detail-cast').textContent = item.cast ? 'Acteurs : '+item.cast : ''; $('detail-director').textContent = item.director ? 'Réalisateur : '+item.director : '';
  $('detail-source').textContent = item.nas ? [item.provider, item.favorite ? 'Favori du compte NAS' : nas.canPlay(item) ? 'Catalogue NAS' : 'Catalogue JojoHome · lecture sur la TV'].filter(Boolean).join(' · ') : item.demo ? 'Fiche de démonstration : aucun flux vidéo associé.' : playableURL(item) ? '' : item.localFile ? 'Choisissez à nouveau ce fichier vidéo pour le lire.' : 'Cette fiche ne contient pas de source vidéo.';
  $('detail-favorite').hidden = Boolean(item.nas);
  $('detail-play').disabled = !playableURL(item); $('detail-favorite').setAttribute('aria-pressed', String(Boolean(item.favorite))); $('detail-favorite').textContent = item.favorite ? 'Retirer des favoris' : 'Ajouter aux favoris';
  openDialog('details-dialog');
  $('episode-list').replaceChildren(); $('episode-list').hidden = true;
  if (item.nas && item.type === 'series' && item.nasType !== 'EPISODE' && nas.canPlay(item)) {
    $('detail-play').disabled = true; $('episode-list').hidden = false; $('episode-list').textContent = 'Chargement des épisodes…';
    try {
      const episodes = await nas.episodes(item); if (detailsId !== id || !$('details-dialog').open) return;
      $('episode-list').replaceChildren(...episodes.map(episode => {
        const button = element('button', 'soft-button', episode.title + (episode.watched ? ' · Déjà vu' : ''));
        button.addEventListener('click', () => playItem(item.nasFavorite ? item : episode, item.nasFavorite ? episode.id : undefined)); return button;
      }));
      if (!episodes.length) $('episode-list').textContent = 'Aucun épisode disponible chez ce fournisseur.';
    } catch (error) { if (detailsId === id) $('episode-list').textContent = error.message; }
  }
}
function playableURL(item) { return item?.nas && nas.canPlay(item) ? 'nas' : localFiles.get(item?.id) || safeURL(item?.url); }
function playItem(item, episode) {
  if (!item) return;
  if (item.nas && item.type === 'series' && item.nasType !== 'EPISODE' && !item.isEpisode && !episode) { openDetails(item.id); return; }
  const source = playableURL(item); if (!source) { openDetails(item.id); return; }
  $('details-dialog').close(); playingId = item.id; playingProfile = profile().id; $('player-title').textContent = item.title; $('player-status').textContent = '';
  openDialog('player-dialog');
  $('player').onloadedmetadata = () => {
    if (item.positionSeconds && Number.isFinite($('player').duration)) $('player').currentTime = Math.min(item.positionSeconds, Math.max(0, $('player').duration-1));
    $('player').play().catch(() => { $('player-status').textContent = 'Appuyez sur lecture pour démarrer la vidéo.'; });
  };
  if (item.nas) nasPlayer.start(item, episode); else $('player').src = source;
}
function saveProgress(force = false) {
  const video = $('player'); if (!playingId || video.readyState === 0 || (!force && Date.now()-lastProgressSave < 5000)) return;
  const item = state.profiles.find(entry => entry.id === playingProfile)?.media.find(entry => entry.id === playingId); if (!item) return;
  item.positionSeconds = Math.max(0, video.currentTime || 0); if (Number.isFinite(video.duration) && video.duration > 0) item.durationMinutes = video.duration / 60;
  lastProgressSave = Date.now(); persist();
}
function renderProfiles() {
  if (nas.connected) { nas.renderAccounts(); return; }
  const options = state.profiles.map(entry => {
    const button = element('button', 'profile-option'); button.setAttribute('aria-pressed', String(entry.id === profile().id));
    button.append(element('span', 'profile-avatar', entry.name.trim().slice(0, 1).toUpperCase() || 'M'), element('span', '', entry.name));
    button.addEventListener('click', () => { state.activeProfile = entry.id; heroIndex = 0; $('library-search').value = ''; persist(); $('accounts-dialog').close(); showPage('home'); }); return button;
  });
  $('profile-list').replaceChildren(...options);
  nas.renderConnection();
}
function renderApps() {
  const links = state.apps.map(app => {
    const link = element('a', '', app.name); link.href = safeURL(app.url); link.target = '_blank'; link.rel = 'noopener noreferrer'; return link;
  });
  $('web-apps').replaceChildren(...(links.length ? links : [element('p', 'muted', 'Ajoutez les raccourcis de vos applications web.')]));
}
function renderSearch() {
  const query = $('global-search').value.trim().toLocaleLowerCase('fr');
  const items = query ? media().filter(item => [item.title, item.cast, item.description].filter(Boolean).join(' ').toLocaleLowerCase('fr').includes(query)).slice(0, 30) : [];
  const results = items.map(item => { const button = element('button', '', item.title); button.append(element('small', '', TYPES[item.type])); button.addEventListener('click', () => { $('search-dialog').close(); openDetails(item.id); }); return button; });
  $('search-results').replaceChildren(...(results.length ? results : [element('p', 'muted', query ? 'Aucun titre trouvé.' : 'Recherchez dans les titres de votre compte.')]));
}
function normalizeCatalog(entries) {
  if (!Array.isArray(entries)) throw new Error('Le catalogue doit contenir une liste de titres.');
  return entries.slice(0, 2000).filter(entry => entry && typeof entry.title === 'string' && entry.title.trim()).map(entry => ({
    id: typeof entry.id === 'string' && entry.id ? entry.id.slice(0, 100) : newId(), title: entry.title.slice(0, 200), type: TYPES[entry.type] ? entry.type : 'videos',
    description: String(entry.description || '').slice(0, 6000), cast: Array.isArray(entry.cast) ? entry.cast.join(', ').slice(0, 2000) : String(entry.cast || '').slice(0, 2000), director: String(entry.director || '').slice(0, 300), genre: String(entry.genre || '').slice(0, 100), year: String(entry.year || '').slice(0, 4),
    episode: String(entry.episode || '').slice(0, 30), artwork: safeURL(entry.artwork), url: safeURL(entry.url), durationMinutes: Math.max(0, Math.min(Number(entry.durationMinutes) || 0, 100000)), positionSeconds: Math.max(0, Number(entry.positionSeconds) || 0), favorite: Boolean(entry.favorite), demo: Boolean(entry.demo)
  }));
}
function addCatalog(entries) {
  const current = localProfile().media; let added = 0;
  for (const item of normalizeCatalog(entries)) { if (!current.some(entry => entry.id === item.id)) { current.push(item); added++; } }
  localProfile().media = current; persist(); renderHome(); if (page !== 'home') renderLibrary(); return added;
}
async function showExample() {
  try { const response = await fetch('./catalog.example.json', { credentials: 'omit' }); if (!response.ok) throw new Error('Exemple indisponible.'); addCatalog(await response.json()); $('settings-dialog').close(); showPage('home'); notice('Exemple affiché : les fiches de démonstration n’ont pas de flux vidéo.'); }
  catch (error) { notice(error.message); }
}
function importCatalog() { $('settings-dialog').close(); $('catalog-file').click(); }

document.querySelectorAll('[data-page]').forEach(button => button.addEventListener('click', () => { $('library-search').value = ''; showPage(button.dataset.page); }));
document.querySelectorAll('[data-close]').forEach(button => button.addEventListener('click', () => $(button.dataset.close).close()));
document.querySelectorAll('dialog').forEach(dialog => dialog.addEventListener('click', event => {
  if (event.target !== dialog) return; const box = dialog.getBoundingClientRect(); if (event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom) dialog.close();
}));
$('account-button').addEventListener('click', () => { renderProfiles(); openDialog('accounts-dialog'); });
$('settings-button').addEventListener('click', () => { applySettings(); openDialog('settings-dialog'); });
$('apps-button').addEventListener('click', () => { renderApps(); openDialog('apps-dialog'); });
$('weather-button').addEventListener('click', () => openDialog('weather-dialog'));
$('search-button').addEventListener('click', () => { $('global-search').value = ''; renderSearch(); openDialog('search-dialog'); $('global-search').focus(); });
$('global-search').addEventListener('input', renderSearch); $('library-search').addEventListener('input', renderLibrary);
$('favorites-filter').addEventListener('click', () => { $('favorites-filter').setAttribute('aria-pressed', String($('favorites-filter').getAttribute('aria-pressed') !== 'true')); renderLibrary(); });
$('hero-previous').addEventListener('click', () => cycleHero(-1)); $('hero-next').addEventListener('click', () => cycleHero(1));
$('hero-play').addEventListener('click', () => playItem(heroItems[heroIndex]));
$('hero-play').addEventListener('keydown', event => { if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') { event.preventDefault(); cycleHero(event.key === 'ArrowLeft' ? -1 : 1); } });
$('detail-play').addEventListener('click', () => { const item = findItem(detailsId); if (item) playItem(item); });
$('detail-favorite').addEventListener('click', () => { const item = media().find(entry => entry.id === detailsId); if (!item) return; item.favorite = !item.favorite; persist(); openDetails(item.id); if (page !== 'home') renderLibrary(); });
$('profile-form').addEventListener('submit', event => { event.preventDefault(); const name = $('profile-name').value.trim(); if (!name) return; state.profiles.push({ id: newId(), name, media: [] }); persist(); $('profile-form').reset(); renderProfiles(); });
$('settings-form').addEventListener('submit', event => { event.preventDefault(); state.settings.color = $('theme-color').value; state.settings.heroSize = $('hero-size').value; applySettings(); persist(); $('settings-dialog').close(); });
$('web-app-form').addEventListener('submit', event => { event.preventDefault(); const url = safeURL($('web-app-url').value); if (!url) return notice('Saisissez une adresse HTTP ou HTTPS.'); state.apps.push({ name: $('web-app-name').value.trim().slice(0, 50), url }); persist(); $('web-app-form').reset(); renderApps(); });
$('import-library').addEventListener('click', importCatalog); $('settings-import').addEventListener('click', importCatalog); $('example-button').addEventListener('click', showExample);
$('catalog-file').addEventListener('change', async () => { const file = $('catalog-file').files[0]; if (!file) return; try { if (file.size > 5000000) throw new Error('Le catalogue doit faire moins de 5 Mo.'); const count = addCatalog(JSON.parse(await file.text())); notice(count+' titres ajoutés au compte '+profile().name+'.'); } catch (error) { notice(error.message); } finally { $('catalog-file').value = ''; } });
$('add-video').addEventListener('click', () => $('video-files').click());
$('video-files').addEventListener('change', () => {
  for (const file of $('video-files').files) {
    const type = page === 'movies' || page === 'series' ? page : 'videos';
    const localFileKey = JSON.stringify([file.name, file.size, file.lastModified]);
    const existing = media().find(entry => entry.type === type && entry.localFileKey === localFileKey);
    const id = existing?.id || newId();
    if (localFiles.has(id)) URL.revokeObjectURL(localFiles.get(id));
    localFiles.set(id, URL.createObjectURL(file));
    if (!existing) media().push({ id, title: file.name.replace(/\.[^.]+$/, ''), type, localFile: true, localFileKey, positionSeconds: 0, description: '', durationMinutes: 0 });
  }
  persist(); renderLibrary(); $('video-files').value = '';
});
$('player').addEventListener('timeupdate', () => saveProgress()); $('player').addEventListener('pause', () => saveProgress(true)); $('player').addEventListener('ended', () => saveProgress(true));
$('player').addEventListener('error', () => { $('player-status').textContent = 'Cette source ne peut pas être lue par le navigateur. Vérifiez son adresse et le format de la vidéo.'; });
$('player-dialog').addEventListener('close', () => { nasPlayer.stop(); saveProgress(true); $('player').pause(); $('player').removeAttribute('src'); $('player').load(); playingId = null; renderHome(); });

document.addEventListener('keydown', event => {
  if (event.defaultPrevented || !['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key) || /INPUT|SELECT|TEXTAREA|VIDEO/.test(event.target.tagName)) return;
  const active = document.activeElement; const box = active.getBoundingClientRect(); const x = box.left+box.width/2, y = box.top+box.height/2;
  const scope = document.querySelector('dialog[open]') || document;
  const candidates = [...scope.querySelectorAll('button:not(:disabled),a[href],input:not([type="file"]),select')].filter(node => node !== active && node.getClientRects().length);
  let best = null, bestScore = Infinity;
  for (const node of candidates) {
    const rect = node.getBoundingClientRect(); const dx = rect.left+rect.width/2-x, dy = rect.top+rect.height/2-y;
    const horizontal = event.key === 'ArrowLeft' || event.key === 'ArrowRight'; const along = horizontal ? dx : dy, across = horizontal ? dy : dx;
    if ((event.key === 'ArrowLeft' || event.key === 'ArrowUp') ? along >= -4 : along <= 4) continue;
    const score = Math.abs(along) + Math.abs(across)*3; if (score < bestScore) { bestScore = score; best = node; }
  }
  if (best) { event.preventDefault(); best.focus(); }
});
window.addEventListener('hashchange', () => showPage(location.hash.slice(1), false));
applySettings(); updateClock(); setInterval(updateClock, 30000); showPage(location.hash.slice(1), false);
if (new URLSearchParams(location.search).get('demo') === '1') showExample();
nas.refresh();
