'use strict';

/* ------------------------------------------------------------ helpers */

const $ = (id) => document.getElementById(id);
const RING_SIZE = 24;
const MODE_TITLES = {
    clock: 'Часы',
    oscilloscope: 'Визуализатор',
    animation: 'Анимация',
    auto: 'Авто',
    test: 'Тест',
    custom: 'Внешний кадр',
    off: 'Выключено',
};
const KIND_TITLES = {
    screen: 'Экран',
    ring: 'Кольцо',
    script: 'Событие',
    service: 'Сервис',
};
const SLOT_TITLES = {
    clock: 'часы',
    visualizer: 'визуализатор',
    animation: 'анимация',
};
const SLOT_META = {
    clock: { select: 'clockSelect', config: 'clockConfig', badge: 'clockBadge', button: 'clockShow' },
    visualizer: { select: 'visualizerSelect', config: 'visualizerConfig', badge: 'visualizerBadge', button: 'visualizerShow' },
    animation: { select: 'animationSelect', config: 'animationConfig', badge: 'animationBadge', button: 'animationShow' },
    ring: { select: 'ringSelect', config: 'ringConfig', badge: 'ringBadge', button: 'ringShow' },
};
const SLOT_SECTION = {
    clock: 'clock',
    visualizer: 'visualizers',
    animation: 'animations',
    ring: 'animations',
};
const CLOCK_SIZE_LABELS = { large: 'Крупные', middle: 'Средние', small: 'Малые' };
const BAND_LABELS = { full: 'Всё', bass: 'Басы', low_mid: 'НЧ–СЧ', mid: 'СЧ', high: 'ВЧ' };
const MEDIA_ANIM_DEFAULTS = {
    enabled: true,
    play: 'media/25x16_Resume.gif',
    pause: 'media/25x16_Pause.gif',
    stop: '',
};
const FALLBACK_ACTIONS = [
    { id: 'none', title: 'Ничего' },
    { id: 'volume_up', title: 'Громкость +' },
    { id: 'volume_down', title: 'Громкость −' },
    { id: 'play_pause', title: 'Пауза / продолжить' },
    { id: 'next', title: 'Следующий трек' },
    { id: 'previous', title: 'Предыдущий трек' },
    { id: 'stop_media', title: 'Остановить медиа' },
    { id: 'alice', title: 'Алиса' },
    { id: 'mute', title: 'Без звука' },
    { id: 'toggle_screen', title: 'Экран вкл/выкл' },
    { id: 'toggle_ring', title: 'Кольцо вкл/выкл' },
    { id: 'clock', title: 'Показать часы' },
    { id: 'visualizer', title: 'Показать визуализатор' },
    { id: 'animation', title: 'Показать анимацию' },
];
const REFRESH_EVENTS = [
    'mode_changed', 'auto_mode', 'screen_select', 'features', 'plugin_toggle',
    'plugin_installed', 'plugin_removed', 'idle_animation', 'ring_pattern',
    'ring_color', 'ring_enabled', 'scope_band', 'clock_size', 'mod_activate',
    'media_anim', 'volume_changed', 'general', 'remap', 'alice',
    'scope_smooth', 'mic_passthrough',
];
const CATALOG_EVENTS = ['screen_select', 'features', 'plugin_toggle', 'mod_activate'];
const KIND_ORDER = ['screen', 'ring', 'script', 'service'];

let catalog = null;
let modData = null;
let statusData = null;
let generalData = null;
let remapData = null;
let animationsData = null;
let mediaAnimValues = { play: '', pause: '', stop: '' };
let activeTab = 'clock';
let previewBusy = false;
let snackbarTimer = 0;
let features = { clock: true, visualizer: true, animation: true, ring: true };
let clockSizeValue = '';
let clockSizeKey = '';
let scopeBandValue = '';
let scopeSmoothValue = 1500;
let ringOriginValue = 12;
let bandKey = '';
let generalLoaded = false;
let generalFailedShown = false;
let remapFailedShown = false;
const saveTimers = {};
const pendingSaves = {};
const eventLines = [];

function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
}

function setText(id, text) {
    const node = $(id);
    if (node && node.textContent !== text) node.textContent = text;
}

function debounce(fn, delay) {
    let timer = 0;
    return (...args) => {
        clearTimeout(timer);
        timer = setTimeout(() => fn(...args), delay);
    };
}

function updateRangeFill(input) {
    const min = Number(input.min || 0);
    const max = Number(input.max || 100);
    const value = Number(input.value || 0);
    const percent = max > min ? ((value - min) / (max - min)) * 100 : 0;
    input.style.setProperty('--fill', `${percent}%`);
}

function formatSmooth(ms) {
    const value = Number(ms || 0);
    if (value <= 0) return 'выкл';
    return `${(value / 1000).toFixed(1).replace('.0', '')} с`;
}

function formatUptime(ms) {
    const seconds = Math.floor(Number(ms || 0) / 1000);
    if (seconds < 60) return `${seconds} с`;
    const minutes = Math.floor(seconds / 60);
    if (minutes < 60) return `${minutes} мин ${seconds % 60} с`;
    return `${Math.floor(minutes / 60)} ч ${minutes % 60} мин`;
}

function markPending(key) {
    pendingSaves[key] = Date.now();
}

function clearPending(key) {
    delete pendingSaves[key];
}

function isPending(key) {
    const started = pendingSaves[key];
    return Boolean(started) && (Date.now() - started) < 2500;
}

/* ----------------------------------------------------------- dialogs */

let mdDialogResolve = null;
let mdDialogPreviousFocus = null;

function mdConfirm(title, text, confirmLabel = 'Подтвердить') {
    return new Promise((resolve) => {
        const scrim = $('mdDialog');
        mdDialogPreviousFocus = document.activeElement;
        $('mdDialogTitle').textContent = title;
        $('mdDialogText').textContent = text;
        $('mdDialogOk').textContent = confirmLabel;
        scrim.hidden = false;
        requestAnimationFrame(() => scrim.classList.add('open'));
        $('mdDialogOk').focus();
        mdDialogResolve = resolve;
    });
}

function closeMdDialog(result) {
    const scrim = $('mdDialog');
    if (scrim.hidden || !mdDialogResolve) return;
    const resolve = mdDialogResolve;
    mdDialogResolve = null;
    scrim.classList.remove('open');
    setTimeout(() => {
        scrim.hidden = true;
        if (mdDialogPreviousFocus && mdDialogPreviousFocus.focus) mdDialogPreviousFocus.focus();
        resolve(result);
    }, 200);
}

$('mdDialogOk').addEventListener('click', () => closeMdDialog(true));
$('mdDialogCancel').addEventListener('click', () => closeMdDialog(false));
$('mdDialog').addEventListener('click', (event) => {
    if (event.target === $('mdDialog')) closeMdDialog(false);
});
document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !$('mdDialog').hidden) closeMdDialog(false);
});

/* --------------------------------------------------------- transport */

async function getJson(path) {
    const response = await fetch(path, { cache: 'no-store' });
    if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`);
    return response.json();
}

async function post(path, body) {
    const response = await fetch(path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body || {}),
        cache: 'no-store',
    });
    let data = {};
    try { data = await response.json(); } catch (_) { data = {}; }
    if (!response.ok && !data.error) data.error = `HTTP ${response.status}`;
    return data;
}

function showSnackbar(text) {
    const bar = $('snackbar');
    bar.textContent = text;
    bar.classList.add('show');
    clearTimeout(snackbarTimer);
    snackbarTimer = setTimeout(() => bar.classList.remove('show'), 2600);
}

function setOnline(online) {
    $('conn').classList.toggle('online', online);
    $('conn').classList.toggle('offline', !online);
    $('connText').textContent = online ? 'на связи' : 'нет связи';
}

/* -------------------------------------------------------------- tabs */

function switchTab(name) {
    activeTab = name;
    document.querySelectorAll('.nav-btn').forEach((tab) => {
        const active = tab.dataset.tab === name;
        tab.classList.toggle('active', active);
        tab.setAttribute('aria-selected', String(active));
    });
    document.querySelectorAll('.tab-panel').forEach((panel) => {
        panel.classList.toggle('active', panel.id === `tab-${name}`);
    });
    if (name === 'general') {
        loadMods(true);
        loadLogs(true);
    } else {
        loadCatalog();
        refreshPreviews();
    }
}

document.querySelectorAll('.nav-btn').forEach((tab) => {
    tab.addEventListener('click', () => switchTab(tab.dataset.tab));
});

/* ---------------------------------------------------------- previews */

function drawRing(bytes) {
    const canvas = $('ringPreview');
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const logical = 120;
    if (canvas.width !== logical * dpr) {
        canvas.width = logical * dpr;
        canvas.height = logical * dpr;
    }
    const ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, logical, logical);

    const cx = logical / 2;
    const cy = logical / 2;
    const radius = logical / 2 - 17;
    const ledRadius = 4.6;

    ctx.beginPath();
    ctx.arc(cx, cy, radius, 0, Math.PI * 2);
    ctx.strokeStyle = 'rgba(230, 224, 233, 0.09)';
    ctx.lineWidth = 1.2;
    ctx.stroke();

    // Physical LED 0 is at the back/bottom; indices grow counter-clockwise,
    // which is decreasing canvas angle on this y-down coordinate system.
    // The clock itself sweeps clockwise.
    const origin = Number(ringOriginValue) || 0;
    const frontAngle = Math.PI / 2 - (origin / RING_SIZE) * Math.PI * 2;
    const markerX = cx + Math.cos(frontAngle) * (radius + 12);
    const markerY = cy + Math.sin(frontAngle) * (radius + 12);
    ctx.beginPath();
    ctx.arc(markerX, markerY, 3.2, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(230, 224, 233, 0.55)';
    ctx.fill();

    for (let i = 0; i < RING_SIZE; i++) {
        const angle = Math.PI / 2 - (i / RING_SIZE) * Math.PI * 2;
        const x = cx + Math.cos(angle) * radius;
        const y = cy + Math.sin(angle) * radius;
        const offset = i * 3;
        const r = bytes[offset] || 0;
        const g = bytes[offset + 1] || 0;
        const b = bytes[offset + 2] || 0;
        const lit = r + g + b > 9;

        ctx.beginPath();
        ctx.arc(x, y, ledRadius, 0, Math.PI * 2);
        if (lit) {
            const color = `rgb(${r}, ${g}, ${b})`;
            ctx.fillStyle = color;
            ctx.shadowColor = color;
            ctx.shadowBlur = 9;
        } else {
            ctx.fillStyle = '#1b1920';
            ctx.shadowBlur = 0;
        }
        ctx.fill();
        ctx.shadowBlur = 0;
    }

    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillStyle = 'rgba(230, 224, 233, 0.55)';
    ctx.font = '500 9px system-ui, sans-serif';
    ctx.fillText('24 LED', cx, cy - 8);
    ctx.fillStyle = 'rgba(230, 224, 233, 0.32)';
    ctx.font = '7.5px system-ui, sans-serif';
    ctx.fillText(`передний · ${origin}`, cx, cy + 8);
}

function base64ToBytes(data) {
    const raw = atob(data || '');
    const out = new Uint8Array(raw.length);
    for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
    return out;
}

async function refreshPreviews() {
    if (previewBusy) return;
    previewBusy = true;
    try {
        $('screenPreview').src = `/api/frame.bmp?t=${Date.now()}`;
        const ring = await getJson('/api/ring');
        drawRing(base64ToBytes(ring.data));
    } catch (_) {
        // Connection state is reported by the status poll.
    } finally {
        previewBusy = false;
    }
}

/* ------------------------------------------------------------ wiring */

function wireSwitch(id, key, send, message) {
    $(id).addEventListener('change', async (event) => {
        const input = event.target;
        const checked = input.checked;
        markPending(key);
        try {
            const result = await send(checked);
            if (result && result.ok === false) throw new Error(result.error || 'не изменено');
            clearPending(key);
            if (message) showSnackbar(message(checked, result));
        } catch (error) {
            input.checked = !checked;
            clearPending(key);
            showSnackbar(error.message);
        }
    });
}

function wireRange(id, key, send, options = {}) {
    const input = $(id);
    const labelId = options.label || null;
    const format = options.format || ((value) => String(value));
    const parse = options.parse || ((value) => Number(value));
    input.addEventListener('input', () => {
        if (labelId) setText(labelId, format(input.value));
        updateRangeFill(input);
        markPending(key);
        clearTimeout(saveTimers[key]);
        saveTimers[key] = setTimeout(async () => {
            try {
                const result = await send(parse(input.value));
                if (result && result.ok === false) throw new Error(result.error || 'не сохранено');
                clearPending(key);
            } catch (error) {
                clearPending(key);
                showSnackbar(`Не сохранено: ${error.message}`);
            }
        }, 300);
    });
}

function setSegmented(container, value) {
    container.querySelectorAll('.segment').forEach((button) => {
        const active = button.dataset.value === String(value);
        button.classList.toggle('active', active);
        button.setAttribute('aria-pressed', String(active));
    });
}

function buildSegmented(container, options, value, onChange) {
    container.innerHTML = '';
    for (const option of options) {
        const button = element('button', 'segment', option.label);
        button.type = 'button';
        button.dataset.value = option.value;
        button.setAttribute('aria-pressed', 'false');
        button.addEventListener('click', () => onChange(option.value, button));
        container.appendChild(button);
    }
    setSegmented(container, value);
}

function sizeOptions(sizes) {
    const list = Array.isArray(sizes) && sizes.length ? sizes : ['large', 'middle', 'small'];
    return list.map((value) => ({ value, label: CLOCK_SIZE_LABELS[value] || value }));
}

function bandOptions(bands) {
    const list = Array.isArray(bands) && bands.length ? bands : ['full', 'bass', 'low_mid', 'mid', 'high'];
    return list.map((value) => ({ value, label: BAND_LABELS[value] || value }));
}

/* ------------------------------------------------------------- status */

function setRingSwitches(value) {
    $('ringEnabled').checked = Boolean(value);
    $('ringPower').checked = Boolean(value);
}

function applyFeatureState(next) {
    features = {
        clock: next.clock !== false,
        visualizer: next.visualizer !== false,
        animation: next.animation !== false,
        ring: next.ring !== false,
    };
    $('featureClock').checked = features.clock;
    $('featureVisualizer').checked = features.visualizer;
    $('featureAnimation').checked = features.animation;
    $('featureRing').checked = features.ring;
    for (const [type, meta] of Object.entries(SLOT_META)) {
        const enabled = features[type];
        const select = $(meta.select);
        const button = $(meta.button);
        if (select) select.disabled = !enabled;
        if (button) button.disabled = !enabled;
        const badge = $(meta.badge);
        if (badge) badge.classList.toggle('warn', !enabled);
    }
}

function updateServiceRows(services) {
    const items = Array.isArray(services) ? services : [];
    document.querySelectorAll('[data-service-status]').forEach((node) => {
        const item = items.find((entry) => entry.key === node.dataset.serviceStatus);
        if (!item) {
            node.textContent = 'нет данных';
            return;
        }
        node.textContent = item.running
            ? `работает · pid ${item.pid} · рестартов ${item.restarts} · ${formatUptime(item.uptime_ms)}`
            : `остановлен${item.last_error ? ` · ${item.last_error}` : ''}`;
        node.classList.toggle('ok', Boolean(item.running));
        node.classList.toggle('error', Boolean(item.last_error) && !item.running);
    });
    const badge = $('serviceBadge');
    if (badge) {
        const running = items.filter((item) => item.running).length;
        badge.textContent = `${running}/${items.length} работает`;
    }
}

async function refreshStatus() {
    try {
        const status = await getJson('/api/status');
        statusData = status;
        setOnline(true);

        const mode = status.mode || '';
        const effective = status.mode_effective || mode;
        const effectiveTitle = status.mode_title || MODE_TITLES[effective] || effective || '—';
        const title = mode === 'auto' ? `Авто → ${effectiveTitle}` : (MODE_TITLES[mode] || mode || '—');
        setText('modeChip', `режим: ${title}`);
        setText('autoBadge', mode === 'auto' ? 'авто' : 'вручную');

        const auto = status.auto || {};
        if (!isPending('mode.auto')) $('autoToggle').checked = Boolean(auto.enabled);
        const packages = Array.isArray(auto.packages) && auto.packages.length
            ? ` · ${auto.packages.join(', ')}`
            : '';
        setText('autoState', auto.enabled
            ? `${auto.playing ? 'играет медиа' : 'тишина'} → ${effectiveTitle}${packages}`
            : 'Ручной режим. Вид выбирается в разделах выше.');

        applyFeatureState(status.features || {});

        const render = status.render || {};
        if (!isPending('render.ring_enabled')) setRingSwitches(render.ring_enabled !== false);

        const colorInput = $('ringColor');
        if (document.activeElement !== colorInput && !isPending('render.ring_color')) {
            const color = String(render.ring_color || '#66d9ff').toLowerCase();
            colorInput.value = color;
            markSwatch(color);
        }

        const originInput = $('ringOrigin');
        if (document.activeElement !== originInput && !isPending('render.ring_origin')) {
            ringOriginValue = Number(render.ring_origin !== undefined ? render.ring_origin : 12);
            originInput.value = String(ringOriginValue);
            setText('ringOriginLabel', originInput.value);
            updateRangeFill(originInput);
        }

        const smoothInput = $('scopeSmooth');
        if (document.activeElement !== smoothInput && !isPending('render.scope_smooth')) {
            scopeSmoothValue = Number(render.scope_smooth !== undefined ? render.scope_smooth : 1500);
            smoothInput.value = String(scopeSmoothValue);
            setText('scopeSmoothLabel', formatSmooth(scopeSmoothValue));
            updateRangeFill(smoothInput);
        }

        const animInput = $('animationColor');
        if (document.activeElement !== animInput && !isPending('render.animation_color')) {
            animInput.value = String(render.animation_color || '#ffffff').toLowerCase();
        }

        const sizeKey = Array.isArray(render.clock_sizes) ? render.clock_sizes.join('|') : '';
        if (sizeKey && sizeKey !== clockSizeKey) {
            clockSizeKey = sizeKey;
            buildSegmented($('clockSize'), sizeOptions(render.clock_sizes), render.clock_size || 'middle', onClockSize);
        } else if (render.clock_size && !isPending('clock.clock_size')) {
            clockSizeValue = render.clock_size;
            setSegmented($('clockSize'), clockSizeValue);
        }

        const sensInput = $('scopeSensitivity');
        if (document.activeElement !== sensInput && !isPending('render.scope_sensitivity')) {
            sensInput.value = String(Number(render.scope_sensitivity !== undefined ? render.scope_sensitivity : 1));
            setText('scopeSensLabel', sensInput.value);
            updateRangeFill(sensInput);
        }

        const fpsInput = $('scopeFps');
        if (document.activeElement !== fpsInput && !isPending('render.scope_fps')) {
            fpsInput.value = String(Number(render.scope_fps || 15));
            setText('scopeFpsLabel', fpsInput.value);
            updateRangeFill(fpsInput);
        }
        setText('scopeFpsActual', `${Number(render.scope_fps_actual || 0)} fps факт`);

        const nextBandKey = Array.isArray(render.scope_bands) ? render.scope_bands.join('|') : '';
        if (nextBandKey && nextBandKey !== bandKey) {
            bandKey = nextBandKey;
            scopeBandValue = render.scope_band || 'full';
            buildSegmented($('scopeBand'), bandOptions(render.scope_bands), scopeBandValue, onScopeBand);
        } else if (nextBandKey && render.scope_band && !isPending('render.scope_band')) {
            scopeBandValue = render.scope_band;
            setSegmented($('scopeBand'), scopeBandValue);
        }

        const ring = status.ring || {};
        setText('ringPatternState', ring.pattern ? `текущий шаблон: ${ring.pattern}` : 'шаблон не выбран');

        const sdk = status.sdk || {};
        const pids = Array.isArray(sdk.pids) ? sdk.pids.join(', ') : '';
        const sdkBadge = $('sdkBadge');
        if (!sdk.running) {
            sdkBadge.textContent = 'не запущен';
            sdkBadge.className = 'badge warn';
            setText('sdk', 'процесс io.sdk не найден');
        } else if (sdk.frozen) {
            sdkBadge.textContent = 'STOP';
            sdkBadge.className = 'badge error';
            setText('sdk', `процесс остановлен SIGSTOP · pid ${pids || '—'}`);
        } else {
            sdkBadge.textContent = 'RUN';
            sdkBadge.className = 'badge ok';
            setText('sdk', `процесс работает · pid ${pids || '—'}`);
        }

        const autostart = status.autostart || {};
        if (!isPending('autostart.enabled')) $('autostartToggle').checked = Boolean(autostart.enabled);
        setText(
            'autostartState',
            autostart.enabled
                ? (autostart.installed ? 'скрипт в /odm установлен' : 'включён, но скрипт не установлен')
                : 'выключен',
        );
        $('autostartBadge').textContent = autostart.installed ? 'установлен' : 'нет';
        $('autostartBadge').className = `badge ${autostart.installed ? 'ok' : 'warn'}`;

        const audio = status.audio || {};
        const volume = Math.round(Number(audio.volume || 0) * 100);
        const volumeInput = $('volume');
        if (document.activeElement !== volumeInput && !isPending('audio.volume')) {
            volumeInput.value = String(volume);
            setText('volumeLabel', `${volume}%`);
            updateRangeFill(volumeInput);
        }
        setText(
            'audioState',
            `захват ${audio.capture ? 'вкл' : 'выкл'} · выход ${audio.output ? 'вкл' : 'выкл'} · ` +
            `${audio.capture_source || '—'}`,
        );

        const mic = status.mic || {};
        const rms = Number(mic.rms || 0);
        setText('micText', `RMS ${(rms * 100).toFixed(2)} % · ${mic.samples || 0} сэмплов`);
        $('meter').style.width = `${Math.min(100, rms * 320)}%`;

        setText(
            'system',
            `root ${status.root ? 'OK' : 'нет'} · SELinux ${status.selinux || '—'} · ` +
            `fb ${status.display && status.display.started ? 'OK' : 'нет'} · ` +
            `input ${status.input && status.input.started ? 'OK' : 'нет'} · ` +
            `http://${status.ip || '127.0.0.1'}:8080 · v${status.version || '—'} · ` +
            `аптайм ${formatUptime(status.uptime_ms)}`,
        );
        setText(
            'screenCaption',
            `${effectiveTitle}${mode === 'auto' ? ' · авто' : ''} · ${render.scope_fps_actual || 0} fps`,
        );

        updateServiceRows((status.services && status.services.items) || []);

        // Graceful consumption of new status fields (never required).
        if (!generalLoaded && status.general) applyGeneral(status.general);
        if (!generalLoaded && status.media_anim) applyMediaAnim(status.media_anim);
        if (!remapData && status.remap && Array.isArray(status.remap.bindings)) renderRemap(status.remap);
    } catch (error) {
        setOnline(false);
        setText('system', String(error.message || error));
    }
}

/* ------------------------------------------------------------ catalog */

function selectedProvider(type) {
    if (!catalog) return null;
    const meta = SLOT_META[type];
    const select = $(meta.select);
    const items = Array.isArray(catalog[type]) ? catalog[type] : [];
    return items.find((provider) => provider.key === select.value) || items[0] || null;
}

function fillSlot(type) {
    const meta = SLOT_META[type];
    const select = $(meta.select);
    const items = Array.isArray(catalog[type]) ? catalog[type] : [];
    const previous = select.value;
    select.innerHTML = '';

    if (!items.length) {
        const option = element('option', '', 'нет доступных виджетов');
        option.value = '';
        select.appendChild(option);
        select.disabled = true;
        $(meta.badge).textContent = '0';
        $(meta.config).innerHTML = '';
        $(meta.button).disabled = true;
        return;
    }

    const groups = new Map();
    for (const provider of items) {
        const group = provider.pack_name || provider.pack || 'Паки';
        if (!groups.has(group)) groups.set(group, []);
        groups.get(group).push(provider);
    }
    for (const [groupName, providers] of groups) {
        const optgroup = document.createElement('optgroup');
        optgroup.label = groupName;
        for (const provider of providers) {
            const option = document.createElement('option');
            option.value = provider.key;
            option.textContent = provider.name + (provider.group ? ` · ${provider.group}` : '');
            optgroup.appendChild(option);
        }
        select.appendChild(optgroup);
    }

    const wanted = (catalog.selection && catalog.selection[type]) || previous;
    if (wanted && items.some((provider) => provider.key === wanted)) {
        select.value = wanted;
    } else {
        select.value = items[0].key;
    }
    select.disabled = !features[type];
    $(meta.button).disabled = !features[type];
    $(meta.badge).textContent = String(items.length);
    $(meta.badge).classList.toggle('warn', !features[type]);
    renderSlotConfig(type);
}

function renderSlotConfig(type) {
    const meta = SLOT_META[type];
    const container = $(meta.config);
    container.innerHTML = '';
    const provider = selectedProvider(type);
    if (!provider) return;
    const fields = Array.isArray(provider.config) ? provider.config : [];
    for (const field of fields) {
        container.appendChild(configField(type, provider, field));
    }
}

function configField(type, provider, field) {
    const row = element('label', 'config-field');
    row.appendChild(element('span', 'config-label', field.label || field.key));
    let input;
    switch (field.kind) {
        case 'boolean': {
            const holder = element('span', 'switch');
            input = document.createElement('input');
            input.type = 'checkbox';
            input.checked = String(field.value) === 'true';
            holder.appendChild(input);
            holder.appendChild(element('span', 'track'));
            row.appendChild(holder);
            break;
        }
        case 'select':
            input = document.createElement('select');
            input.className = 'select';
            for (const option of field.options || []) {
                const item = document.createElement('option');
                item.value = option.value;
                item.textContent = option.label || option.value;
                input.appendChild(item);
            }
            input.value = String(field.value);
            break;
        case 'range':
        case 'number': {
            input = document.createElement('input');
            input.type = field.kind === 'range' ? 'range' : 'number';
            if (field.min !== null && field.min !== undefined) input.min = String(field.min);
            if (field.max !== null && field.max !== undefined) input.max = String(field.max);
            if (field.step !== null && field.step !== undefined) input.step = String(field.step);
            input.value = String(field.value);
            if (field.kind === 'range') input.className = 'range';
            if (field.kind === 'range') updateRangeFill(input);
            break;
        }
        case 'color':
            input = document.createElement('input');
            input.type = 'color';
            input.value = String(field.value || '#ffffff');
            break;
        default:
            input = document.createElement('input');
            input.type = 'text';
            input.value = String(field.value || '');
    }
    if (field.kind !== 'boolean') {
        input.dataset.field = field.key;
        input.dataset.kind = field.kind || 'text';
        row.appendChild(input);
    } else {
        input.dataset.field = field.key;
        input.dataset.kind = field.kind || 'text';
    }

    if (field.kind === 'range') {
        const output = element('output', 'config-value', String(field.value));
        input.addEventListener('input', () => { output.textContent = input.value; });
        row.appendChild(output);
    }
    if (field.unit) row.appendChild(element('span', 'config-unit', field.unit));
    if (field.help) row.appendChild(element('small', 'config-help', field.help));

    const save = () => {
        clearTimeout(saveTimers[type]);
        saveTimers[type] = setTimeout(() => saveSlotConfig(type, provider), 300);
    };
    input.addEventListener('input', save);
    input.addEventListener('change', save);
    return row;
}

function collectConfig(container) {
    const values = {};
    container.querySelectorAll('[data-field]').forEach((input) => {
        values[input.dataset.field] = input.type === 'checkbox'
            ? (input.checked ? 'true' : 'false')
            : input.value;
    });
    return values;
}

async function saveSlotConfig(type, provider) {
    const meta = SLOT_META[type];
    const container = $(meta.config);
    const values = collectConfig(container);
    if (!Object.keys(values).length) return;
    try {
        const result = await post('/api/mods/config', { provider: provider.key, values });
        if (result.ok === false) throw new Error(result.error || 'ошибка');
        for (const field of provider.config || []) {
            if (values[field.key] !== undefined) field.value = values[field.key];
        }
        showSnackbar(`Настройки сохранены: ${provider.name}`);
        refreshPreviews();
    } catch (error) {
        showSnackbar(`Не сохранено: ${error.message}`);
    }
}

async function loadCatalog(force) {
    if (catalog && !force) {
        for (const type of Object.keys(SLOT_META)) fillSlot(type);
        return catalog;
    }
    try {
        catalog = await getJson('/api/catalog');
        for (const type of Object.keys(SLOT_META)) fillSlot(type);
        applyFeatureState(catalog.features || {});
    } catch (error) {
        for (const type of Object.keys(SLOT_META)) {
            $(SLOT_META[type].badge).textContent = 'нет связи';
        }
        showSnackbar(`Каталог: ${error.message}`);
    }
    return catalog;
}

async function selectSlot(type, providerKey, loop) {
    if (!providerKey) return;
    if (!features[type]) {
        showSnackbar('Этот вид выключен — включите его переключателем выше');
        return;
    }
    try {
        const result = await post('/api/screen', { type, provider: providerKey, loop: loop !== false });
        if (result.ok === false) throw new Error(result.error || 'не удалось выбрать');
        if (catalog && catalog.selection) catalog.selection[type] = providerKey;
        renderSlotConfig(type);
        showSnackbar(`Выбрано: ${providerKey}`);
        refreshStatus();
        refreshPreviews();
    } catch (error) {
        showSnackbar(`Не выбрано: ${error.message}`);
    }
}

for (const type of Object.keys(SLOT_META)) {
    const meta = SLOT_META[type];
    $(meta.select).addEventListener('change', () => {
        const provider = selectedProvider(type);
        if (provider) selectSlot(type, provider.key, true);
    });
    $(meta.button).addEventListener('click', () => {
        const provider = selectedProvider(type);
        if (provider) selectSlot(type, provider.key, true);
    });
}

$('animationOnce').addEventListener('click', async () => {
    const provider = selectedProvider('animation');
    if (!provider) return;
    try {
        const result = await post('/api/plugins/activate', { provider: provider.key, loop: false });
        if (!result.ok) throw new Error('анимация не запущена');
        showSnackbar(`Проигрывается: ${provider.name}`);
        refreshPreviews();
    } catch (error) {
        showSnackbar(error.message);
    }
});

$('ringStop').addEventListener('click', async () => {
    try {
        await post('/api/ring/pattern', {});
        showSnackbar('Шаблон кольца остановлен');
        refreshStatus();
        refreshPreviews();
    } catch (error) {
        showSnackbar(`Ошибка: ${error.message}`);
    }
});

const saveAnimationColor = debounce(async (color) => {
    markPending('render.animation_color');
    try {
        await post('/api/render', { animation_color: color });
        clearPending('render.animation_color');
        showSnackbar('Цвет анимации сохранён');
        refreshPreviews();
    } catch (error) {
        clearPending('render.animation_color');
        showSnackbar(`Цвет не сохранён: ${error.message}`);
    }
}, 300);

$('animationColor').addEventListener('input', (event) => saveAnimationColor(event.target.value));

/* --------------------------------------------------- render settings */

function markSwatch(color) {
    document.querySelectorAll('.swatch').forEach((swatch) => {
        swatch.classList.toggle('active', swatch.dataset.color.toLowerCase() === String(color).toLowerCase());
    });
}

const saveRingColor = debounce(async (color) => {
    markPending('render.ring_color');
    try {
        await post('/api/render', { ring_color: color });
        clearPending('render.ring_color');
        showSnackbar('Цвет кольца сохранён');
        refreshPreviews();
    } catch (error) {
        clearPending('render.ring_color');
        showSnackbar(`Цвет не сохранён: ${error.message}`);
    }
}, 300);

$('ringColor').addEventListener('input', (event) => {
    markSwatch(event.target.value);
    saveRingColor(event.target.value);
});

document.querySelectorAll('.swatch').forEach((swatch) => {
    swatch.addEventListener('click', () => {
        $('ringColor').value = swatch.dataset.color;
        markSwatch(swatch.dataset.color);
        saveRingColor(swatch.dataset.color);
    });
});

wireRange('ringOrigin', 'render.ring_origin', (value) => post('/api/render', { ring_origin: value }), {
    label: 'ringOriginLabel',
});

const sendRingEnabled = async (value) => {
    const result = await post('/api/render', { ring_enabled: value });
    if (result.ok !== false) {
        setRingSwitches(value);
        refreshPreviews();
    }
    return result;
};
wireSwitch('ringEnabled', 'render.ring_enabled', sendRingEnabled,
    (value) => `Кольцо ${value ? 'включено' : 'выключено'}`);
wireSwitch('ringPower', 'render.ring_enabled', sendRingEnabled,
    (value) => `Кольцо ${value ? 'включено' : 'выключено'}`);

wireRange('scopeSensitivity', 'render.scope_sensitivity',
    (value) => post('/api/render', { scope_sensitivity: value }), { label: 'scopeSensLabel' });

wireRange('scopeSmooth', 'render.scope_smooth',
    (value) => post('/api/render', { scope_smooth: value }),
    { label: 'scopeSmoothLabel', format: formatSmooth });

wireRange('scopeFps', 'render.scope_fps', (value) => post('/api/render', { scope_fps: value }), {
    label: 'scopeFpsLabel',
});

function onScopeBand(value) {
    const previous = scopeBandValue;
    scopeBandValue = value;
    markPending('render.scope_band');
    setSegmented($('scopeBand'), value);
    post('/api/render', { scope_band: value }).then((result) => {
        if (result.ok === false) throw new Error(result.error || 'не сохранено');
        clearPending('render.scope_band');
        showSnackbar(`Полоса: ${BAND_LABELS[value] || value}`);
    }).catch((error) => {
        scopeBandValue = previous;
        setSegmented($('scopeBand'), previous);
        clearPending('render.scope_band');
        showSnackbar(`Не сохранено: ${error.message}`);
    });
}

function onClockSize(value) {
    const previous = clockSizeValue;
    clockSizeValue = value;
    markPending('clock.clock_size');
    setSegmented($('clockSize'), value);
    post('/api/clock', { size: value }).then((result) => {
        if (result.ok === false) throw new Error(result.error || 'не сохранено');
        clearPending('clock.clock_size');
        showSnackbar(`Размер часов: ${CLOCK_SIZE_LABELS[value] || value}`);
    }).catch((error) => {
        clockSizeValue = previous;
        setSegmented($('clockSize'), previous);
        clearPending('clock.clock_size');
        showSnackbar(`Не сохранено: ${error.message}`);
    });
}

buildSegmented($('clockSize'), sizeOptions(), '', onClockSize);
buildSegmented($('scopeBand'), bandOptions(), '', onScopeBand);

wireSwitch('autoToggle', 'mode.auto', (value) => post('/api/mode', {
    mode: value ? 'auto' : ((statusData && statusData.mode_effective) || 'clock'),
}), (value) => (value ? 'Авто включено' : 'Авто выключено'));

/* ------------------------------------------------------ general */

function applyGeneral(data) {
    if (!data || typeof data !== 'object') return;
    generalLoaded = true;
    if ('master' in data && !isPending('general.master')) $('masterToggle').checked = Boolean(data.master);
    if ('screen' in data && !isPending('general.screen')) $('screenToggle').checked = Boolean(data.screen);
    if ('mic_share' in data && !isPending('general.mic_share')) $('micShare').checked = Boolean(data.mic_share);
    if ('mic_passthrough' in data && !isPending('general.mic_passthrough')) {
        $('micPassthrough').checked = Boolean(data.mic_passthrough);
    }
    const ringVolume = data.ring_volume;
    if (ringVolume && typeof ringVolume === 'object') {
        if ('enabled' in ringVolume && !isPending('general.ring_volume_enabled')) {
            $('ringVolumeToggle').checked = Boolean(ringVolume.enabled);
        }
        if ('step' in ringVolume && !isPending('general.ring_volume_step')) {
            const stepInput = $('ringVolumeStep');
            stepInput.value = String(Math.min(5, Math.max(1, Number(ringVolume.step) || 1)));
            setText('ringVolumeStepLabel', stepInput.value);
            updateRangeFill(stepInput);
        }
        if ('anim' in ringVolume && !isPending('general.ring_volume_anim')) {
            $('ringVolumeAnim').checked = Boolean(ringVolume.anim);
        }
    }
    if (data.media_anim !== undefined) applyMediaAnim(data.media_anim);
    if ('alice_available' in data) {
        const available = Boolean(data.alice_available);
        $('alice').disabled = !available;
        setText('aliceState', available
            ? 'нажмите, чтобы активировать Алису'
            : 'Алиса недоступна на устройстве');
    }
}

async function loadGeneral() {
    try {
        const data = await getJson('/api/general');
        if (data.ok === false) throw new Error(data.error || 'недоступно');
        applyGeneral(data);
    } catch (error) {
        if (!generalFailedShown) {
            generalFailedShown = true;
            showSnackbar(`Общие настройки: ${error.message}`);
        }
        if (!generalLoaded) applyMediaAnim(null);
    }
}

wireSwitch('masterToggle', 'general.master', async (value) => {
    const result = await post('/api/general', { master: value });
    if (result.ok !== false && result && 'master' in result) applyGeneral(result);
    refreshStatus();
    return result;
}, (value) => `Питание панели ${value ? 'включено' : 'выключено'}`);

wireSwitch('screenToggle', 'general.screen', (value) => post('/api/general', { screen: value }),
    (value) => `Экран ${value ? 'включён' : 'выключён'}`);

wireSwitch('micShare', 'general.mic_share', (value) => post('/api/general', { mic_share: value }),
    (value) => (value ? 'Микрофон отдаётся системе' : 'Микрофон возвращён визуализатору'));

wireSwitch('micPassthrough', 'general.mic_passthrough',
    (value) => post('/api/general', { mic_passthrough: value }),
    (value) => (value ? 'Микрофон проброшен системным приложениям' : 'Микрофон возвращён io.sdk'));

wireSwitch('ringVolumeToggle', 'general.ring_volume_enabled',
    (value) => post('/api/general', { ring_volume_enabled: value }),
    (value) => `Кольцо → громкость ${value ? 'включено' : 'выключено'}`);

wireSwitch('ringVolumeAnim', 'general.ring_volume_anim',
    (value) => post('/api/general', { ring_volume_anim: value }),
    (value) => `Анимация громкости ${value ? 'включена' : 'выключена'}`);

wireRange('ringVolumeStep', 'general.ring_volume_step',
    (value) => post('/api/general', { ring_volume_step: value }), { label: 'ringVolumeStepLabel' });

wireRange('volume', 'audio.volume', (value) => post('/api/volume', { value: value / 100 }), {
    label: 'volumeLabel',
    format: (value) => `${value}%`,
});

$('tone').addEventListener('click', async () => {
    try {
        const result = await post('/api/tone', { frequency: 440, ms: 400 });
        showSnackbar(result.ok ? 'Тон 440 Гц отправлен' : 'Тон не воспроизведён');
    } catch (error) {
        showSnackbar(`Ошибка: ${error.message}`);
    }
});

$('alice').addEventListener('click', async () => {
    try {
        const result = await post('/api/alice', {});
        if (!result.ok) throw new Error(result.error || 'Алиса не ответила');
        showSnackbar('Алиса активирована');
    } catch (error) {
        showSnackbar(`Алиса: ${error.message}`);
    }
});

/* --------------------------------------------------- media animations */

async function loadAnimations() {
    try {
        animationsData = await getJson('/api/animations');
        buildMediaAnimOptions();
    } catch (_) {
        // Pickers keep only the "no animation" option.
    }
}

function buildMediaAnimOptions() {
    if (!animationsData || !Array.isArray(animationsData.groups)) return;
    for (const [field, id] of [
        ['play', 'mediaAnimPlay'],
        ['pause', 'mediaAnimPause'],
        ['stop', 'mediaAnimStop'],
    ]) {
        const select = $(id);
        const previous = select.value;
        select.innerHTML = '';
        const empty = element('option', '', 'нет анимации');
        empty.value = '';
        select.appendChild(empty);
        for (const group of animationsData.groups) {
            const clips = Array.isArray(group.clips) ? group.clips : [];
            if (!clips.length) continue;
            const optgroup = document.createElement('optgroup');
            optgroup.label = group.group || 'клипы';
            for (const clip of clips) {
                const option = document.createElement('option');
                option.value = clip;
                option.textContent = clip;
                optgroup.appendChild(option);
            }
            select.appendChild(optgroup);
        }
        if (previous && !Array.from(select.options).some((option) => option.value === previous)) {
            const option = document.createElement('option');
            option.value = previous;
            option.textContent = previous;
            select.appendChild(option);
        }
        select.value = previous || '';
    }
}

function applyMediaAnim(mediaAnim) {
    const data = mediaAnim && typeof mediaAnim === 'object'
        && (mediaAnim.play !== undefined || mediaAnim.pause !== undefined || mediaAnim.stop !== undefined)
        ? mediaAnim
        : MEDIA_ANIM_DEFAULTS;
    const enabled = data.enabled !== undefined ? Boolean(data.enabled) : true;
    if (!isPending('media_anim.enabled')) $('mediaAnimEnabled').checked = enabled;
    for (const field of ['play', 'pause', 'stop']) {
        if (!(field in data)) continue;
        const value = data[field] == null ? '' : String(data[field]);
        const select = $(field === 'play' ? 'mediaAnimPlay' : field === 'pause' ? 'mediaAnimPause' : 'mediaAnimStop');
        if (isPending(`media_anim.${field}`)) continue;
        if (value && !Array.from(select.options).some((option) => option.value === value)) {
            const option = document.createElement('option');
            option.value = value;
            option.textContent = value;
            select.appendChild(option);
        }
        select.value = value;
        mediaAnimValues[field] = value;
    }
}

wireSwitch('mediaAnimEnabled', 'media_anim.enabled', async (value) => {
    const result = await post('/api/media-anim', { enabled: value });
    if (result.ok !== false) {
        const fresh = result && result.media_anim ? result.media_anim : result;
        if (fresh && typeof fresh === 'object') applyMediaAnim(fresh);
    }
    return result;
}, (value) => `Автоанимации ${value ? 'включены' : 'выключены'}`);

for (const [field, id] of [
    ['play', 'mediaAnimPlay'],
    ['pause', 'mediaAnimPause'],
    ['stop', 'mediaAnimStop'],
]) {
    $(id).addEventListener('change', async (event) => {
        const select = event.target;
        const value = select.value;
        markPending(`media_anim.${field}`);
        try {
            const result = await post('/api/media-anim', { [field]: value });
            if (result.ok === false) throw new Error(result.error || 'не сохранено');
            clearPending(`media_anim.${field}`);
            mediaAnimValues[field] = value;
            const fresh = result && result.media_anim ? result.media_anim : result;
            if (fresh && typeof fresh === 'object' && ('play' in fresh || 'enabled' in fresh)) {
                applyMediaAnim(fresh);
            }
            showSnackbar(value ? `Анимация «${field}» сохранена` : `Анимация «${field}» отключена`);
        } catch (error) {
            clearPending(`media_anim.${field}`);
            select.value = mediaAnimValues[field];
            showSnackbar(`Не сохранено: ${error.message}`);
        }
    });
}

/* -------------------------------------------------------------- remap */

function remapOptions() {
    const seen = new Set();
    const list = [];
    const push = (id, title) => {
        if (!id || seen.has(id)) return;
        seen.add(id);
        list.push({ id, title: title || id });
    };
    const base = remapData && Array.isArray(remapData.actions) && remapData.actions.length
        ? remapData.actions
        : FALLBACK_ACTIONS;
    for (const action of base) push(action.id, action.title);
    if (modData && Array.isArray(modData.mods)) {
        for (const mod of modData.mods) {
            for (const item of mod.items || []) {
                if (item.kind === 'script') push(`script:${item.key}`, `Скрипт: ${item.name || item.key}`);
            }
        }
    }
    if (animationsData && Array.isArray(animationsData.groups)) {
        for (const group of animationsData.groups) {
            for (const clip of group.clips || []) push(`anim:${clip}`, `Анимация: ${clip}`);
        }
    }
    return list;
}

function fillRemapOptions(select, current) {
    select.innerHTML = '';
    for (const option of remapOptions()) {
        const node = document.createElement('option');
        node.value = option.id;
        node.textContent = option.title;
        select.appendChild(node);
    }
    if (current && !Array.from(select.options).some((option) => option.value === current)) {
        const node = document.createElement('option');
        node.value = current;
        node.textContent = current;
        select.appendChild(node);
    }
    select.value = current || 'none';
}

function renderRemap(data) {
    remapData = data || remapData;
    const container = $('remapList');
    container.innerHTML = '';
    const bindings = remapData && Array.isArray(remapData.bindings) ? remapData.bindings : [];
    if (!bindings.length) {
        container.appendChild(element('p', 'support', 'Нет данных о кнопках.'));
        return;
    }
    for (const binding of bindings) {
        const row = element('div', 'remap-row');
        const main = element('div', 'remap-main');
        main.appendChild(element('b', '', binding.title || binding.key));
        if (binding.device) main.appendChild(element('span', 'remap-device', binding.device));
        const select = document.createElement('select');
        select.className = 'select compact';
        fillRemapOptions(select, binding.action);
        select.addEventListener('change', async () => {
            const previous = binding.action;
            markPending('remap');
            try {
                const result = await post('/api/remap', { key: binding.key, action: select.value });
                if (result.ok === false) throw new Error(result.error || 'не сохранено');
                clearPending('remap');
                if (result && Array.isArray(result.bindings)) remapData = result;
                else binding.action = select.value;
                showSnackbar(`${binding.title || binding.key}: ${select.value}`);
            } catch (error) {
                clearPending('remap');
                select.value = previous;
                showSnackbar(`Не сохранено: ${error.message}`);
            }
        });
        row.appendChild(main);
        row.appendChild(select);
        container.appendChild(row);
    }
}

async function loadRemap() {
    try {
        const data = await getJson('/api/remap');
        if (data.ok === false) throw new Error(data.error || 'недоступно');
        renderRemap(data);
    } catch (error) {
        if (!remapFailedShown) {
            remapFailedShown = true;
            showSnackbar(`Переназначение кнопок: ${error.message}`);
        }
    }
}

$('remapReset').addEventListener('click', async () => {
    const confirmed = await mdConfirm(
        'Сбросить переназначение?',
        'Все кнопки вернутся к штатным действиям.',
        'Сбросить',
    );
    if (!confirmed) return;
    try {
        const result = await post('/api/remap', { reset: true });
        if (result.ok === false) throw new Error(result.error || 'не удалось сбросить');
        if (result && Array.isArray(result.bindings)) renderRemap(result);
        else await loadRemap();
        showSnackbar('Переназначение сброшено');
    } catch (error) {
        showSnackbar(`Не сброшено: ${error.message}`);
    }
});

/* --------------------------------------------------- system controls */

$('freeze').addEventListener('click', async () => {
    try {
        const result = await post('/api/freeze', { action: 'freeze' });
        showSnackbar(result.ok ? 'io.sdk остановлен (SIGSTOP)' : 'io.sdk не остановлен');
        refreshStatus();
    } catch (error) {
        showSnackbar(`Ошибка: ${error.message}`);
    }
});

$('unfreeze').addEventListener('click', async () => {
    try {
        const result = await post('/api/freeze', { action: 'unfreeze' });
        showSnackbar(result.ok ? 'io.sdk продолжен (SIGCONT)' : 'io.sdk не продолжен');
        refreshStatus();
    } catch (error) {
        showSnackbar(`Ошибка: ${error.message}`);
    }
});

$('restartPipeline').addEventListener('click', async () => {
    try {
        await post('/api/pipeline/restart', {});
        showSnackbar('Пайплайн перезапускается');
        setTimeout(refreshStatus, 1500);
    } catch (error) {
        showSnackbar(`Ошибка: ${error.message}`);
    }
});

wireSwitch('autostartToggle', 'autostart.enabled', (value) => post('/api/autostart', { enabled: value }),
    (value, result) => (result && result.detail ? `Автозапуск: ${result.detail}`
        : `Автозапуск ${value ? 'включён' : 'выключен'}`));

async function setIcon(visible) {
    try {
        const result = await post(`/api/icon?visible=${visible}`, {});
        showSnackbar(result.ok ? (visible ? 'Иконка возвращена' : 'Иконка скрыта') : 'Не удалось');
    } catch (error) {
        showSnackbar(`Ошибка: ${error.message}`);
    }
}

$('hideIcon').addEventListener('click', () => setIcon(false));
$('showIcon').addEventListener('click', () => setIcon(true));

/* --------------------------------------------------------------- mods */

function serviceSwitch(checked, onChange) {
    const label = element('label', 'switch');
    const input = document.createElement('input');
    input.type = 'checkbox';
    input.checked = checked;
    input.addEventListener('change', () => onChange(input.checked));
    label.appendChild(input);
    label.appendChild(element('span', 'track'));
    return label;
}

function serviceButton(text, onClick, className) {
    const button = element('button', `button ${className || 'tonal'}`, text);
    button.type = 'button';
    button.addEventListener('click', onClick);
    return button;
}

async function togglePack(packId, enabled) {
    try {
        const result = await post('/api/mods/enable', { pack: packId, enabled });
        if (result.ok === false) throw new Error(result.error || 'не изменено');
        showSnackbar(`Мод ${packId}: ${enabled ? 'включён' : 'выключен'}`);
        await loadMods(true);
        await loadCatalog(true);
        refreshStatus();
    } catch (error) {
        showSnackbar(error.message);
    }
}

async function toggleProvider(key, enabled) {
    try {
        const result = await post('/api/mods/enable', { provider: key, enabled });
        if (result.ok === false) throw new Error(result.error || 'не изменено');
        showSnackbar(`${key}: ${enabled ? 'включён' : 'выключен'}`);
        await loadMods(true);
        await loadCatalog(true);
        refreshStatus();
    } catch (error) {
        showSnackbar(error.message);
    }
}

async function serviceAction(key, action) {
    try {
        const result = await post('/api/mods/service', { provider: key, action });
        showSnackbar(result.ok ? `Сервис ${key}: ${action}` : `Сервис ${key}: ошибка`);
        await loadMods(true);
        refreshStatus();
    } catch (error) {
        showSnackbar(error.message);
    }
}

async function modActivate(item, loop = true) {
    try {
        const result = await post('/api/mods/activate', { item, loop });
        if (result.ok === false) throw new Error(result.error || 'не удалось включить');
        showSnackbar(`Включено: ${item}`);
        await loadCatalog(true);
        refreshStatus();
        refreshPreviews();
    } catch (error) {
        showSnackbar(`Не включено: ${error.message}`);
    }
}

async function openProviderInScreen(key, type) {
    switchTab(SLOT_SECTION[type] || 'general');
    await loadCatalog(true);
    const meta = SLOT_META[type];
    if (!meta) return;
    const select = $(meta.select);
    const option = Array.from(select.options).find((entry) => entry.value === key);
    if (!option) {
        showSnackbar('Предмет отключён или недоступен');
        return;
    }
    select.value = key;
    renderSlotConfig(type);
    await selectSlot(type, key, true);
}

function itemLabel(item) {
    if (item.kind === 'screen' && item.slot) {
        return `${KIND_TITLES.screen} · ${SLOT_TITLES[item.slot] || item.slot}`;
    }
    return KIND_TITLES[item.kind] || item.kind;
}

function itemRow(item, service) {
    const row = element('div', 'provider-row');
    const main = element('div', 'provider-main');
    const title = element('b', '', item.name);
    title.appendChild(element('span', 'type-badge', itemLabel(item)));
    main.appendChild(title);
    if (item.description) main.appendChild(element('p', '', item.description));
    if (item.kind === 'service') {
        const status = element(
            'p',
            'service-meta',
            service
                ? (service.running ? `работает · pid ${service.pid}` : 'остановлен')
                : 'нет данных',
        );
        status.dataset.serviceStatus = item.key;
        main.appendChild(status);
    }
    if (item.kind === 'script') {
        const events = Array.isArray(item.events) && item.events.length
            ? item.events.join(', ')
            : 'не подписан';
        main.appendChild(element('p', 'service-meta', `${item.entry || '—'} · события: ${events}`));
    }
    row.appendChild(main);

    const actions = element('div', 'provider-actions');
    if (item.kind === 'service') {
        actions.appendChild(serviceButton('Старт', () => serviceAction(item.key, 'start'), 'tonal'));
        actions.appendChild(serviceButton('Стоп', () => serviceAction(item.key, 'stop'), 'text'));
        actions.appendChild(serviceButton('Логи', () => openPluginLogs(item.key), 'text'));
    } else if (item.kind === 'script') {
        actions.appendChild(serviceButton('Запустить', () => modActivate(item.key, false), 'tonal'));
        actions.appendChild(serviceButton('Логи', () => openPluginLogs(item.key), 'text'));
    } else {
        actions.appendChild(serviceButton('Включить', () => modActivate(item.key, true), 'tonal'));
        if (item.kind === 'screen') {
            actions.appendChild(serviceButton('Настроить', () => openProviderInScreen(item.key, item.slot), 'text'));
        }
    }
    actions.appendChild(serviceSwitch(item.enabled !== false, (enabled) => toggleProvider(item.key, enabled)));
    row.appendChild(actions);
    return row;
}

function renderServices(data) {
    const container = $('serviceList');
    container.innerHTML = '';
    const services = (data.services && data.services.items) || [];
    if (!services.length) {
        container.appendChild(element(
            'p',
            'support',
            'Фоновых сервисов нет. Добавьте предмет с kind = service и полем entry в манифест мода.',
        ));
        return;
    }
    for (const service of services) {
        const item = element('div', 'service-item');
        const head = element('div', 'service-head');
        const title = element('b', '', service.name);
        title.appendChild(element('span', 'type-badge', service.pack));
        head.appendChild(title);
        const badge = element('span', `badge ${service.running ? 'ok' : 'warn'}`,
            service.running ? 'работает' : 'остановлен');
        head.appendChild(badge);
        item.appendChild(head);
        item.appendChild(element(
            'p',
            'service-meta',
            `${service.key} · pid ${service.pid || '—'} · рестартов ${service.restarts} · ` +
            `${service.running ? formatUptime(service.uptime_ms) : '—'} · политика ${service.restart}`,
        ));
        if (service.last_error) item.appendChild(element('p', 'service-meta error', service.last_error));
        const actions = element('div', 'service-actions');
        actions.appendChild(serviceButton('Старт', () => serviceAction(service.key, 'start'), 'tonal'));
        actions.appendChild(serviceButton('Стоп', () => serviceAction(service.key, 'stop'), 'text'));
        actions.appendChild(serviceButton('Рестарт', () => serviceAction(service.key, 'restart'), 'text'));
        actions.appendChild(serviceButton('Логи', () => openPluginLogs(service.key), 'text'));
        item.appendChild(actions);
        container.appendChild(item);
    }
}

function renderMods(data) {
    const container = $('pluginPacks');
    container.innerHTML = '';
    const mods = Array.isArray(data.mods) ? data.mods : [];
    const services = (data.services && data.services.items) || [];
    const totalItems = mods.reduce((sum, mod) => sum + (mod.items ? mod.items.length : 0), 0);
    setText('pluginBadge', `${mods.length} мод. · ${totalItems} предмет.`);
    setText(
        'pluginState',
        `модов: ${mods.length} · встроенных: ${mods.filter((mod) => mod.builtin).length} · ` +
        `пользовательских: ${mods.filter((mod) => !mod.builtin).length}`,
    );

    for (const mod of mods) {
        const box = element('div', 'plugin-pack');
        if (mod.error) box.classList.add('error');
        const head = element('div', 'plugin-pack-head');
        const title = element('div', 'plugin-pack-title');
        title.appendChild(element('b', '', mod.name));
        title.appendChild(element('span', 'badge', `v${mod.version}${mod.builtin ? ' · встроенный' : ''}`));
        title.appendChild(element('span', `badge ${mod.enabled === false ? 'warn' : 'ok'}`,
            mod.enabled === false ? 'выкл' : 'вкл'));
        head.appendChild(title);

        const controls = element('div', 'provider-actions');
        controls.appendChild(serviceSwitch(mod.enabled !== false, (enabled) => togglePack(mod.id, enabled)));
        if (!mod.builtin) {
            controls.appendChild(serviceButton('Удалить', async () => {
                const confirmed = await mdConfirm(
                    `Удалить мод ${mod.name}?`,
                    'Предметы и файлы мода будут удалены. Действие необратимо.',
                    'Удалить',
                );
                if (!confirmed) return;
                try {
                    const result = await post('/api/mods/remove', { id: mod.id });
                    if (!result.ok) throw new Error('мод не удалён');
                    showSnackbar(`Удалён: ${mod.name}`);
                    await loadMods(true);
                    await loadCatalog(true);
                    refreshStatus();
                } catch (error) {
                    showSnackbar(error.message);
                }
            }, 'text error'));
        }
        head.appendChild(controls);
        box.appendChild(head);
        if (mod.author) box.appendChild(element('p', 'support', mod.author));
        if (mod.description) box.appendChild(element('p', 'support', mod.description));
        if (mod.error) box.appendChild(element('p', 'support error', mod.error));

        const items = Array.isArray(mod.items) ? mod.items : [];
        for (const kind of KIND_ORDER) {
            const group = items.filter((item) => item.kind === kind);
            if (!group.length) continue;
            const details = document.createElement('details');
            details.className = 'plugin-group';
            details.open = kind === 'service' || kind === 'script';
            details.appendChild(element('summary', '', `${KIND_TITLES[kind] || kind} · ${group.length}`));
            for (const item of group) {
                const service = services.find((entry) => entry.key === item.key);
                details.appendChild(itemRow(item, service));
            }
            box.appendChild(details);
        }
        if (!items.length) box.appendChild(element('p', 'support', 'Нет предметов.'));
        container.appendChild(box);
    }
    renderServices(data);
    updateServiceRows(services);
}

async function loadMods(force) {
    if (modData && !force) return modData;
    try {
        modData = await getJson('/api/mods');
        renderMods(modData);
    } catch (error) {
        setText('pluginBadge', 'недоступно');
        setText('pluginState', String(error.message || error));
    }
    return modData;
}

$('pluginInstall').addEventListener('click', async () => {
    const input = $('pluginFile');
    const file = input.files && input.files[0];
    if (!file) {
        showSnackbar('Выберите zip-архив мода');
        return;
    }
    try {
        const response = await fetch('/api/mods/install', {
            method: 'POST',
            headers: { 'Content-Type': 'application/zip' },
            body: await file.arrayBuffer(),
            cache: 'no-store',
        });
        const result = await response.json();
        if (!result.ok) throw new Error(result.message || result.error || 'ошибка установки');
        showSnackbar(result.message || 'Мод установлен');
        input.value = '';
        await loadMods(true);
        await loadCatalog(true);
        refreshStatus();
    } catch (error) {
        showSnackbar(`Мод не установлен: ${error.message}`);
    }
});

/* --------------------------------------------------------------- logs */

let pendingLogKey = null;

function openPluginLogs(key) {
    pendingLogKey = key;
    switchTab('general');
    const card = $('logsCard');
    if (card) card.open = true;
    $('logScope').value = 'plugin';
    $('logPlugin').disabled = false;
    if (card && card.scrollIntoView) card.scrollIntoView({ behavior: 'smooth', block: 'start' });
    loadLogs(true);
}

async function loadLogs(force) {
    const scope = $('logScope').value;
    if (!force && activeTab !== 'general') return;
    $('logPlugin').disabled = scope !== 'plugin';
    let plugin = $('logPlugin').value;
    if (pendingLogKey) {
        plugin = pendingLogKey;
        pendingLogKey = null;
    }
    const query = new URLSearchParams({ scope, limit: '400' });
    if (scope === 'plugin' && plugin) query.set('plugin', plugin);

    try {
        const data = await getJson(`/api/logs?${query.toString()}`);
        const keys = Array.isArray(data.plugins) ? data.plugins : [];
        const select = $('logPlugin');
        const previous = select.value;
        select.innerHTML = '';
        for (const key of keys) {
            const option = document.createElement('option');
            option.value = key;
            option.textContent = key;
            select.appendChild(option);
        }
        if (plugin && !keys.includes(plugin)) {
            const option = document.createElement('option');
            option.value = plugin;
            option.textContent = plugin;
            select.appendChild(option);
        }
        select.value = plugin || previous || keys[0] || '';

        const lines = Array.isArray(data.lines) ? data.lines : [];
        const output = $('logOutput');
        const atBottom = output.scrollTop + output.clientHeight >= output.scrollHeight - 24;
        output.textContent = lines.length ? lines.join('\n') : '(пусто)';
        if ($('logFollow').checked || atBottom) output.scrollTop = output.scrollHeight;
        setText(
            'logState',
            `${data.scope === 'plugin' ? `плагин ${data.plugin || '—'}` : 'глобальный лог'} · ${lines.length} строк`,
        );
    } catch (error) {
        setText('logState', String(error.message || error));
        $('logOutput').textContent = `не удалось загрузить логи: ${error.message}`;
    }
}

$('logScope').addEventListener('change', () => loadLogs(true));
$('logPlugin').addEventListener('change', () => loadLogs(true));
$('logRefresh').addEventListener('click', () => loadLogs(true));
$('logFollow').addEventListener('change', () => loadLogs(true));
$('logClear').addEventListener('click', async () => {
    const scope = $('logScope').value;
    const plugin = scope === 'plugin' ? $('logPlugin').value : null;
    const confirmed = await mdConfirm(
        'Очистить лог?',
        scope === 'plugin' ? `Будут удалены строки лога плагина ${plugin || '—'}.` : 'Будут удалены строки глобального лога.',
        'Очистить',
    );
    if (!confirmed) return;
    try {
        await post('/api/logs/clear', { scope, plugin });
        showSnackbar('Лог очищен');
        await loadLogs(true);
    } catch (error) {
        showSnackbar(`Ошибка: ${error.message}`);
    }
});

/* ------------------------------------------------------------- events */

function appendEventFeed(event) {
    eventLines.push(event);
    if (eventLines.length > 50) eventLines.splice(0, eventLines.length - 50);
    renderEventFeed();
}

function renderEventFeed() {
    const container = $('eventsList');
    if (!container) return;
    container.textContent = '';
    for (let i = eventLines.length - 1; i >= 0; i--) {
        const event = eventLines[i];
        const date = new Date(Number(event.ts) || Date.now());
        const pad = (value) => String(value).padStart(2, '0');
        const stamp = `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
        const fields = Object.assign({}, event);
        delete fields.event;
        delete fields.ts;
        let rest = Object.keys(fields).length ? JSON.stringify(fields) : '';
        if (rest.length > 160) rest = `${rest.slice(0, 157)}…`;
        container.appendChild(element('div', 'event-line', `[${stamp}] ${event.event || '—'} ${rest}`.trim()));
    }
}

function connectEvents() {
    const source = new EventSource('/api/events');
    source.onmessage = (message) => {
        try {
            const event = JSON.parse(message.data);
            const name = event.event || '';
            appendEventFeed(event);
            if (REFRESH_EVENTS.includes(name)) {
                refreshStatus();
                refreshPreviews();
                if (CATALOG_EVENTS.includes(name)) loadCatalog(true);
                if (activeTab === 'general'
                    && ['plugin_toggle', 'plugin_installed', 'plugin_removed'].includes(name)) {
                    loadMods(true);
                }
            } else if (name.startsWith('plugin_')) {
                refreshStatus();
            }
            if (['media_anim', 'general', 'alice'].includes(name)) loadGeneral();
            if (['remap', 'general'].includes(name)) loadRemap();
        } catch (_) {
            // Ignore malformed events; the status poll still works.
        }
    };
    source.onerror = () => {
        setOnline(false);
        source.close();
        setTimeout(connectEvents, 3000);
    };
}

/* --------------------------------------------------------------- init */

document.querySelectorAll('input[type=range].range').forEach(updateRangeFill);
markSwatch($('ringColor').value);
applyMediaAnim(null);

refreshStatus();
loadGeneral();
loadRemap();
loadAnimations();
loadCatalog();
refreshPreviews();
loadMods();
connectEvents();

setInterval(refreshStatus, 2000);
setInterval(refreshPreviews, 800);
setInterval(() => {
    if (activeTab === 'general' && $('logFollow').checked) loadLogs(false);
}, 2500);
