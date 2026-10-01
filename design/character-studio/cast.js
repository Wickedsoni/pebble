// Pebble cast v2 — clay creatures with limbs, kawaii eyes and soft studio lighting.
// Every layer is an ellipse / path / gradient / gaussian blur, so it ports 1:1 to Compose Canvas.

const shades = {
  pebble: ['#c9ecff', '#6ec6ff', '#2f7fd6'],
  mochi:  ['#ffe6ee', '#ffb3c7', '#d9668a'],
  sprout: ['#e0f9d2', '#9be38a', '#4ea851'],
  bolt:   ['#ffffff', '#cdd6e3', '#7e8ca3'],
  drip:   ['#ecfaff', '#86d6f8', '#2f95cf'],
};

// Shared defs live in one hidden <svg>; Chrome resolves url(#id) across inline SVGs.
document.body.insertAdjacentHTML('afterbegin', `
<svg width="0" height="0" style="position:absolute"><defs>
  ${[0.8, 3, 7, 12].map((d, i) => `<filter id="blur${i}" x="-60%" y="-60%" width="220%" height="220%"><feGaussianBlur stdDeviation="${d}"/></filter>`).join('')}
  <linearGradient id="iris" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#14162b"/><stop offset=".65" stop-color="#262c55"/><stop offset="1" stop-color="#4b5fb0"/>
  </linearGradient>
  <linearGradient id="irisBot" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#0b2236"/><stop offset="1" stop-color="#1b6f8c"/>
  </linearGradient>
  <radialGradient id="screenGlow" cx="50%" cy="50%" r="60%">
    <stop offset="0" stop-color="#5ef2ff" stop-opacity=".35"/><stop offset="1" stop-color="#5ef2ff" stop-opacity="0"/>
  </radialGradient>
</defs></svg>`);

let uid = 0;
const nid = (p) => p + (uid++);

function baseGrad(c, cx = '38%', cy = '28%') {
  const id = nid('g');
  return { id, def: `<radialGradient id="${id}" cx="${cx}" cy="${cy}" r="85%">
    <stop offset="0" stop-color="${c[0]}"/><stop offset=".5" stop-color="${c[1]}"/><stop offset="1" stop-color="${c[2]}"/></radialGradient>` };
}

const SHAPES = {
  gumdrop: 'M100 44 C150 44 172 88 172 126 C172 162 140 178 100 178 C60 178 28 162 28 126 C28 88 50 44 100 44 Z',
  bean:    'M100 32 C140 32 160 72 160 120 C160 162 134 178 100 178 C66 178 40 162 40 120 C40 72 60 32 100 32 Z',
  droplet: 'M100 26 C112 56 168 96 168 132 C168 162 138 180 100 180 C62 180 32 162 32 132 C32 96 88 56 100 26 Z',
};

/** Clay body: base gradient + ambient occlusion + rim light + soft & crisp speculars. */
function clay(path, c, { glassy = false } = {}) {
  const g = baseGrad(c), clip = nid('c');
  return `<defs>${g.def}<clipPath id="${clip}"><path d="${path}"/></clipPath></defs>
    <path d="${path}" fill="url(#${g.id})" ${glassy ? 'opacity=".9"' : ''}/>
    <g clip-path="url(#${clip})">
      <ellipse cx="104" cy="196" rx="86" ry="34" fill="${c[2]}" opacity=".55" filter="url(#blur3)"/>
      <path d="${path}" fill="none" stroke="${c[0]}" stroke-width="9" opacity="${glassy ? .9 : .6}" transform="translate(-6 -7)" filter="url(#blur1)"/>
      <ellipse cx="80" cy="76" rx="40" ry="22" fill="#fff" opacity="${glassy ? .45 : .32}" transform="rotate(-25 80 76)" filter="url(#blur2)"/>
    </g>
    <ellipse cx="70" cy="80" rx="15" ry="7.5" fill="#fff" opacity=".9" transform="rotate(-30 70 80)" filter="url(#blur0)"/>
    <circle cx="57" cy="97" r="3.6" fill="#fff" opacity=".75"/>
    <path d="${path}" fill="none" stroke="${c[2]}" stroke-opacity=".2" stroke-width="1.5"/>`;
}

/** Little limb blob with its own lighting. */
function nub(cx, cy, rx, ry, rot, c, cls = '') {
  const g = baseGrad(c, '35%', '25%');
  return `<g class="${cls}" transform="rotate(${rot} ${cx} ${cy})"><defs>${g.def}</defs>
    <ellipse cx="${cx}" cy="${cy + 2}" rx="${rx}" ry="${ry}" fill="${c[2]}" opacity=".35" filter="url(#blur1)"/>
    <ellipse cx="${cx}" cy="${cy}" rx="${rx}" ry="${ry}" fill="url(#${g.id})"/>
    <ellipse cx="${cx - rx * .3}" cy="${cy - ry * .45}" rx="${rx * .35}" ry="${ry * .22}" fill="#fff" opacity=".7" filter="url(#blur0)"/></g>`;
}

const feet = (c, spread = 26, y = 178) => nub(100 - spread, y, 17, 10, 0, c) + nub(100 + spread, y, 17, 10, 0, c);

function arms(c, pose = 'down', w = 72) {
  const L = 100 - w, R = 100 + w;
  const down = (x, s) => nub(x, 136, 11, 17, s * 22, c);
  const up = (x, s) => nub(x + s * 6, 96, 10.5, 17, s * 38, c);
  switch (pose) {
    case 'up':   return up(L, -1) + up(R, 1);
    case 'wave': return down(L, -1) + `<g class="wave2" style="transform-origin:${R - 4}px 112px">${up(R, 1)}</g>`;
    case 'hold': return down(L, -1) + nub(R - 22, 146, 11, 16, -60, c) + glass(R - 6, 128);
    case 'hug':  return nub(L + 20, 146, 11, 16, 60, c) + nub(R - 20, 146, 11, 16, -60, c);
    default:     return down(L, -1) + down(R, 1);
  }
}

function glass(x, y) {
  return `<g transform="translate(${x} ${y})">
    <path d="M-12 0 L12 0 L9 30 Q0 34 -9 30 Z" fill="#e8f7ff" fill-opacity=".55" stroke="#9fd6f5" stroke-width="2"/>
    <path d="M-10.5 10 L10.5 10 L9 30 Q0 34 -9 30 Z" fill="#6ec6ff" opacity=".75"/>
    <path d="M-7 4 L-5.5 26" stroke="#fff" stroke-width="2.5" opacity=".8" stroke-linecap="round"/></g>`;
}

function eye(x, y, iris = 'iris') {
  return `<g class="blink">
    <ellipse cx="${x}" cy="${y}" rx="11.5" ry="14.5" fill="url(#${iris})"/>
    <ellipse cx="${x}" cy="${y + 7}" rx="7.5" ry="5" fill="#8fa8ff" opacity=".45" filter="url(#blur0)"/>
    <circle cx="${x + 3.8}" cy="${y - 5.5}" r="5" fill="#fff"/>
    <circle cx="${x - 4.2}" cy="${y + 5.5}" r="2.2" fill="#fff" opacity=".9"/></g>`;
}

function face(mood = 'idle', y = 116, spread = 23) {
  const L = 100 - spread, R = 100 + spread, ink = '#23243a';
  const cheeks = `<ellipse cx="${L - 15}" cy="${y + 19}" rx="12" ry="7" fill="#ff6f9c" opacity=".45" filter="url(#blur1)"/>
                  <ellipse cx="${R + 15}" cy="${y + 19}" rx="12" ry="7" fill="#ff6f9c" opacity=".45" filter="url(#blur1)"/>`;
  const stroke = `stroke="${ink}" stroke-width="4.5" fill="none" stroke-linecap="round"`;
  const eyes = eye(L, y) + eye(R, y);
  const happy = `<path d="M${L - 10} ${y + 3} Q${L} ${y - 10} ${L + 10} ${y + 3}" ${stroke}/><path d="M${R - 10} ${y + 3} Q${R} ${y - 10} ${R + 10} ${y + 3}" ${stroke}/>`;
  const closed = `<path d="M${L - 10} ${y} Q${L} ${y + 8} ${L + 10} ${y}" ${stroke}/><path d="M${R - 10} ${y} Q${R} ${y + 8} ${R + 10} ${y}" ${stroke}/>`;
  const sadLids = `<path d="M${L - 10} ${y + 2} Q${L} ${y - 4} ${L + 10} ${y + 4}" ${stroke}/><path d="M${R - 10} ${y + 4} Q${R} ${y - 4} ${R + 10} ${y + 2}" ${stroke}/>`;
  const focus = `<path d="M${L - 10} ${y} h20" ${stroke} stroke-width="5"/><path d="M${R - 10} ${y} h20" ${stroke} stroke-width="5"/>`;
  const my = y + 20;
  const mouths = {
    smile: `<path d="M93 ${my} Q100 ${my + 7} 107 ${my}" stroke="${ink}" stroke-width="3.5" fill="none" stroke-linecap="round"/>`,
    grin: `<path d="M89 ${my - 2} Q100 ${my + 16} 111 ${my - 2} Z" fill="${ink}"/><ellipse cx="100" cy="${my + 7}" rx="5" ry="3" fill="#ff7aa2"/>`,
    o: `<ellipse cx="100" cy="${my + 3}" rx="4.5" ry="5.5" fill="${ink}"/>`,
    wobble: `<path d="M89 ${my + 3} q5.5 -5 11 0 t11 0" stroke="${ink}" stroke-width="3.5" fill="none" stroke-linecap="round"/>`,
    tongue: `<path d="M91 ${my - 1} Q100 ${my + 7} 109 ${my - 1}" stroke="${ink}" stroke-width="3.5" fill="none" stroke-linecap="round"/><path d="M99 ${my + 3} q4.5 12 9 0" fill="#ff7aa2" stroke="${ink}" stroke-width="2"/>`,
    frown: `<path d="M93 ${my + 5} Q100 ${my - 2} 107 ${my + 5}" stroke="${ink}" stroke-width="3.5" fill="none" stroke-linecap="round"/>`,
  };
  const set = {
    idle: eyes + mouths.smile, happy: happy + mouths.grin, sleepy: closed + mouths.o, thirsty: eyes + mouths.tongue,
    worried: eyes + mouths.wobble, sad: sadLids + mouths.frown, celebrate: happy + mouths.grin,
    working: focus + mouths.smile, input: eyes + mouths.o, love: happy + mouths.smile,
  };
  return cheeks + (set[mood] || set.idle);
}

const groundShadow = (rx = 62) => `<ellipse cx="100" cy="190" rx="${rx}" ry="9" fill="#3b2f6b" opacity=".13" filter="url(#blur2)"/>
  <ellipse cx="100" cy="188" rx="${rx * .7}" ry="5" fill="#3b2f6b" opacity=".16" filter="url(#blur1)"/>`;
const svg = (inner) => `<svg viewBox="0 -6 200 206">${inner}</svg>`;
const tile = (inner, name, role) => `<div class="tile">${inner}<div class="name">${name}</div><div class="role">${role}</div></div>`;

// ---------------- characters ----------------
function blobCreature({ c, shape = 'gumdrop', mood = 'idle', pose = 'down', anim = 'breathe', back = '', front = '', top = '', eyeY = 116, glassy = false, armW = 72 }) {
  return svg(groundShadow() + `<g class="${anim}">` + back + feet(c) + clay(SHAPES[shape], c, { glassy }) + top + face(mood, eyeY) + arms(c, pose, armW) + front + `</g>`);
}

const pebble = (mood, opts = {}) => blobCreature({ c: shades.pebble, mood, ...opts });

function mochi(mood = 'idle', opts = {}) {
  const c = shades.mochi, g = baseGrad(c);
  const ears = `<defs>${g.def}</defs>
    <path d="M50 82 L44 30 Q46 22 55 27 L94 54 Z" fill="url(#${g.id})"/>
    <path d="M150 82 L156 30 Q154 22 145 27 L106 54 Z" fill="url(#${g.id})"/>
    <path d="M57 66 L55 40 L78 55 Z" fill="#ff8fb0" opacity=".75" filter="url(#blur0)"/>
    <path d="M143 66 L145 40 L122 55 Z" fill="#ff8fb0" opacity=".75" filter="url(#blur0)"/>`;
  const whiskers = `<g stroke="#23243a" stroke-width="2" stroke-linecap="round" opacity=".45">
    <path d="M44 132 h-16"/><path d="M44 140 l-14 5"/><path d="M156 132 h16"/><path d="M156 140 l14 5"/></g>`;
  return blobCreature({ c, mood, back: ears, front: whiskers, ...opts });
}

function sprout(mood = 'idle', opts = {}) {
  const leaf = `<path d="M100 34 C100 20 104 12 110 8" stroke="#3f8a43" stroke-width="5" fill="none" stroke-linecap="round"/>
    <path d="M108 12 C124 0 146 6 148 16 C134 28 116 26 108 12 Z" fill="#7fd36b"/><path d="M112 13 C124 9 136 12 144 16" stroke="#5fb257" stroke-width="1.5" fill="none"/>
    <path d="M102 16 C90 2 70 6 66 14 C78 26 96 26 102 16 Z" fill="#9be38a"/>`;
  return blobCreature({ c: shades.sprout, shape: 'bean', mood, top: leaf, eyeY: 110, armW: 62, ...opts });
}

function drip(mood = 'idle', opts = {}) {
  const refraction = `<path d="M68 96 C62 112 60 126 64 142" stroke="#fff" stroke-width="7" fill="none" stroke-linecap="round" opacity=".65" filter="url(#blur0)"/>`;
  return blobCreature({ c: shades.drip, shape: 'droplet', mood, glassy: true, top: refraction, eyeY: 128, armW: 70, ...opts });
}

function bolt(mood = 'idle', { pose = 'down', anim = 'breathe' } = {}) {
  const c = shades.bolt, g = baseGrad(c), m = nid('m');
  const metal = `<defs>${g.def}<linearGradient id="${m}" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#fff"/><stop offset=".5" stop-color="#cdd6e3"/><stop offset="1" stop-color="#8794aa"/></linearGradient></defs>`;
  const feetB = `<rect x="62" y="166" width="30" height="20" rx="9" fill="url(#${m})"/><rect x="108" y="166" width="30" height="20" rx="9" fill="url(#${m})"/>`;
  const body = `
    <line x1="100" y1="46" x2="100" y2="22" stroke="#8794aa" stroke-width="5" stroke-linecap="round"/>
    <circle cx="100" cy="18" r="10" fill="#ffb347"/><circle cx="100" cy="18" r="16" fill="#ffb347" opacity=".25" filter="url(#blur1)"/>
    <circle cx="96.5" cy="14.5" r="3.4" fill="#fff" opacity=".8"/>
    <rect x="32" y="44" width="136" height="130" rx="42" fill="url(#${g.id})"/>
    <rect x="32" y="44" width="136" height="130" rx="42" fill="none" stroke="#7e8ca3" stroke-opacity=".35" stroke-width="2"/>
    <ellipse cx="66" cy="64" rx="22" ry="9" fill="#fff" opacity=".85" transform="rotate(-18 66 64)" filter="url(#blur0)"/>
    <rect x="48" y="78" width="104" height="72" rx="24" fill="#16202e"/>
    <rect x="48" y="78" width="104" height="72" rx="24" fill="url(#screenGlow)"/>
    <rect x="48" y="78" width="104" height="72" rx="24" fill="none" stroke="#0a111b" stroke-width="3"/>
    <path d="M60 86 Q100 80 140 86" stroke="#fff" stroke-opacity=".12" stroke-width="5" fill="none" stroke-linecap="round"/>`;
  const glow = '#5ef2ff';
  const eyes = mood === 'happy' || mood === 'celebrate'
    ? `<path d="M72 116 Q81 102 90 116" stroke="${glow}" stroke-width="6" fill="none" stroke-linecap="round"/><path d="M110 116 Q119 102 128 116" stroke="${glow}" stroke-width="6" fill="none" stroke-linecap="round"/>`
    : `<g class="blink"><rect x="70" y="98" width="20" height="24" rx="9" fill="${glow}"/><rect x="70" y="98" width="20" height="24" rx="9" fill="${glow}" filter="url(#blur1)"/><circle cx="84" cy="104" r="3.5" fill="#fff"/></g>
       <g class="blink"><rect x="110" y="98" width="20" height="24" rx="9" fill="${glow}"/><rect x="110" y="98" width="20" height="24" rx="9" fill="${glow}" filter="url(#blur1)"/><circle cx="124" cy="104" r="3.5" fill="#fff"/></g>`;
  const mouth = `<path d="M92 132 Q100 139 108 132" stroke="${glow}" stroke-width="4" fill="none" stroke-linecap="round"/>`;
  const cheeks = `<ellipse cx="62" cy="134" rx="8" ry="4" fill="#ff6f9c" opacity=".55" filter="url(#blur0)"/><ellipse cx="138" cy="134" rx="8" ry="4" fill="#ff6f9c" opacity=".55" filter="url(#blur0)"/>`;
  return svg(groundShadow() + `<g class="${anim}">` + metal + feetB + body + eyes + mouth + cheeks + arms(c, pose, 74) + `</g>`);
}

// ---------------- accessories ----------------
const crown = `<g transform="translate(70 22)"><path d="M0 26 L6 4 L18 18 L30 0 L42 18 L54 4 L60 26 Z" fill="#ffd166" stroke="#e0a800" stroke-width="2.5" stroke-linejoin="round"/>
  <path d="M4 22 L56 22" stroke="#fff" stroke-opacity=".5" stroke-width="3"/><circle cx="30" cy="16" r="4.5" fill="#ef476f"/></g>`;
const scarf = `<path d="M40 150 Q100 174 160 150 L162 163 Q100 189 38 163 Z" fill="#ef476f"/><path d="M130 162 l10 24 l14 -4 l-8 -22 z" fill="#d63d61"/>
  <path d="M48 156 Q100 176 152 156" stroke="#fff" stroke-opacity=".3" stroke-width="2.5" fill="none"/>`;
const tuft = `<path d="M100 46 C98 32 104 24 112 22" stroke="#2f7fd6" stroke-width="5" fill="none" stroke-linecap="round"/><circle cx="114" cy="22" r="8" fill="#9be38a"/><circle cx="111" cy="19" r="2.5" fill="#fff" opacity=".7"/>`;
const headphones = `<path d="M38 112 C38 48 162 48 162 112" stroke="#6b5cff" stroke-width="9" fill="none" stroke-linecap="round"/>
  <rect x="22" y="98" width="24" height="38" rx="12" fill="#8f83ff"/><rect x="154" y="98" width="24" height="38" rx="12" fill="#8f83ff"/>
  <rect x="26" y="102" width="6" height="18" rx="3" fill="#fff" opacity=".5"/>`;
const aura = `<circle cx="100" cy="112" r="94" fill="none" stroke="#ffd166" stroke-width="3" stroke-dasharray="4 10" opacity=".7"/>`;
const sparkle = (x, y, s = 1, col = '#ffd166') => `<g transform="translate(${x} ${y}) scale(${s})"><path class="twinkle" d="M0 -12 Q2 -2 12 0 Q2 2 0 12 Q-2 2 -12 0 Q-2 -2 0 -12Z" fill="${col}"/></g>`;
const zzz = `<g class="float-z" font-family="Nunito" font-weight="900" fill="#6b6f8a"><text x="146" y="58" font-size="22">z</text><text x="162" y="40" font-size="15">z</text></g>`;
const sweat = `<path d="M152 70 C155 78 160 82 160 87 a8 8 0 0 1 -16 0 c0 -5 5 -9 8 -17z" fill="#d6f0ff" stroke="#6ec6ff" stroke-width="2"/>`;
const hardhat = `<path d="M52 64 Q100 18 148 64 Z" fill="#ffc43d" stroke="#e0a000" stroke-width="2.5"/><rect x="42" y="60" width="116" height="12" rx="6" fill="#ffb000"/>
  <path d="M70 52 Q86 34 104 32" stroke="#fff" stroke-opacity=".55" stroke-width="4" fill="none" stroke-linecap="round"/>`;
const bang = `<g><circle cx="168" cy="50" r="16" fill="#ef476f"/><text x="162" y="58" font-family="Nunito" font-weight="900" font-size="22" fill="#fff">!</text></g>`;
const tear = `<path d="M70 132 C72 140 76 144 76 148 a6 6 0 0 1 -12 0 c0 -4 4 -8 6 -16z" fill="#9fdcff" stroke="#6ec6ff" stroke-width="1.5"/>`;
const heart = (x, y, s = 1) => `<path class="twinkle" transform="translate(${x} ${y}) scale(${s})" d="M0 6 C-10 -4 -4 -12 0 -6 C4 -12 10 -4 0 6Z" fill="#ff5d8f"/>`;

// ---------------- render ----------------
document.getElementById('cast').innerHTML = [
  tile(pebble('idle'), 'Pebble', 'Default buddy · calm & cheerful'),
  tile(mochi('idle'), 'Mochi', 'Cat-blob · cosy & cuddly'),
  tile(sprout('idle', { pose: 'wave' }), 'Sprout', 'Habits & routine coach'),
  tile(bolt('idle'), 'Bolt', 'Dev buddy · CI & agents'),
  tile(drip('idle'), 'Drip', 'Hydration hero'),
].join('');

document.getElementById('evo').innerHTML = [
  tile(svg(groundShadow(40) + `<g class="breathe"><g transform="translate(30 56) scale(.7)">` + feet(shades.pebble) + clay(SHAPES.gumdrop, shades.pebble) + face('idle') + arms(shades.pebble) + `</g></g>`), 'Baby', 'Lv 1–4'),
  tile(pebble('happy', { top: tuft }), 'Teen', 'Lv 5–14 · sprout tuft'),
  tile(pebble('idle', { top: headphones, front: scarf }), 'Adult', 'Lv 15–29 · scarf & cans'),
  tile(svg(aura) .replace('</svg>', '') + pebble('happy', { pose: 'up', top: crown, front: scarf }).replace('<svg viewBox="0 -6 200 206">', '') + sparkle(30, 60) + sparkle(172, 120, .8) + sparkle(40, 160, .6) + '</svg>', 'Legendary', 'Lv 30+ · crown & aura'),
].join('');

document.getElementById('moods').innerHTML = [
  tile(pebble('idle'), 'Idle', 'breathes & blinks'),
  tile(pebble('happy', { anim: 'hop', pose: 'up' }), 'Happy', 'task done'),
  tile(pebble('sleepy', { front: zzz }), 'Sleepy', 'bedtime / idle'),
  tile(pebble('thirsty', { pose: 'hold' }), 'Thirsty', 'water reminder'),
  tile(pebble('worried', { anim: 'shake', front: sweat }), 'Worried', 'deadline close'),
  tile(pebble('sad', { front: tear }), 'Sad', 'missed things'),
  tile(pebble('celebrate', { anim: 'hop', pose: 'up', front: sparkle(30, 50) + sparkle(170, 44, .9, '#ef476f') + sparkle(176, 140, .7, '#06d6a0') }), 'Celebrate', 'CI passed · streak'),
  tile(pebble('working', { top: hardhat }), 'Working', 'CI / agent running'),
  tile(pebble('input', { pose: 'wave', front: bang }), 'Needs you', 'agent needs input'),
  tile(mochi('love', { pose: 'hug', front: heart(160, 60) + heart(40, 70, .8) }), 'Cheer-up', 'when you feel low'),
  tile(bolt('happy', { pose: 'up', anim: 'hop' }), 'Bolt · CI ✅', 'build passed'),
  tile(drip('happy', { pose: 'wave' }), 'Drip · sip!', 'hydration nudge'),
].join('');

document.getElementById('bentoPet').innerHTML = pebble('happy', { anim: 'hop', pose: 'wave' }).replace('<svg', '<svg style="max-width:150px;width:100%"');
