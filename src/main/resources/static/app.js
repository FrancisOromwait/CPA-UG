'use strict';

/* ------------------------------------------------------------------ state & helpers */
const S = { auth: null, me: null, single: false, appName: 'CPA Uganda' };   // single: a one-branch installation
try { S.auth = sessionStorage.getItem('auth'); } catch (e) { /* storage blocked */ }

const $ = (sel, el = document) => el.querySelector(sel);
const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const money = n => Number(n || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const label = s => String(s || '').replace(/_/g, ' ').toLowerCase().replace(/^\w/, c => c.toUpperCase());
const today = () => new Date().toISOString().slice(0, 10);
const isMgr = () => S.me.role === 'MANAGER';
const isMember = () => S.me.role === 'MEMBER';          // a member's own login: their statement only
const isBranchAdmin = () => S.me.role === 'BRANCH_ADMIN';   // the one branch role that manages users
/** Who keeps the chart of accounts: the manager, or in a one-branch installation the branch administrator. */
const canChart = () => isMgr() || (S.single && isBranchAdmin());
const SOURCES = [['CASH', 'Cash'], ['BANK', 'Bank'], ['MOBILE_MONEY', 'Mobile money']];

async function api(path, opts = {}) {
  const headers = {};
  if (S.auth) headers.Authorization = S.auth;
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(path, {
    method: opts.method || 'GET',
    headers,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined
  });
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) { /* not json */ }
  if (res.status === 401 && !opts.login) { signOut(); throw new Error('Please sign in again'); }
  if (!res.ok) {
    if (res.status === 403 && !(data && data.error)) throw new Error('You are not allowed to do that');
    throw new Error((data && data.error) || `Request failed (${res.status})`);
  }
  return data;
}

let toastTimer;
function toast(msg, isErr) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'toast' + (isErr ? ' err' : '');
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 3500);
}

/** View for everyone. Edit and Delete for branch admins, except the chart of accounts, which only the manager changes. */
function rowBtns(kind, id, extra = '') {
  const canChange = kind === 'account' ? canChart() : !isMgr();
  return `<div class="actions"><button class="btn small" data-act="view" data-kind="${kind}" data-id="${id}">View</button>` +
    (!canChange ? '' : `<button class="btn small" data-act="edit" data-kind="${kind}" data-id="${id}">Edit</button>` +
      `<button class="btn small danger" data-act="del" data-kind="${kind}" data-id="${id}">Delete</button>`) + extra + `</div>`;
}

function table(cols, rows, foot, grid) {
  if (!rows.length) return '<div class="empty">Nothing here yet.</div>';
  const head = cols.map(c => `<th class="${c.num ? 'num' : ''}">${esc(c.h)}</th>`).join('');
  const body = rows.map(r => `<tr>${cols.map(c => `<td class="${c.num ? 'num' : ''}">${c.f(r)}</td>`).join('')}</tr>`).join('');
  return `<div class="tbl-wrap"><table${grid ? ' class="grid"' : ''}><thead><tr>${head}</tr></thead><tbody>${body}</tbody>${foot || ''}</table></div>`;
}

const inp = (name, lbl, o = {}) =>
  `<label>${esc(lbl)}<input name="${name}" type="${o.type || 'text'}" ${o.req ? 'required' : ''}` +
  `${o.step ? ` step="${o.step}"` : ''}${o.min !== undefined ? ` min="${o.min}"` : ''}${o.max !== undefined ? ` max="${o.max}"` : ''}` +
  ` value="${esc(o.value ?? '')}"${o.ph ? ` placeholder="${esc(o.ph)}"` : ''}></label>`;

const sel = (name, lbl, opts, o = {}) =>
  `<label>${esc(lbl)}<select name="${name}" ${o.req ? 'required' : ''}>` +
  `${o.blank !== undefined ? `<option value="">${esc(o.blank)}</option>` : ''}` +
  `${opts.map(([v, t]) => `<option value="${esc(v)}">${esc(t)}</option>`).join('')}</select></label>`;

const memberOptions = members => members.map(m => [m.id, `${m.memberNo} – ${m.fullName}`]);
const fd = form => Object.fromEntries(new FormData(form));

function modal({ title, body, submit = 'Save', wide = false, onMount, onSubmit }) {
  const root = $('#modalRoot');
  root.innerHTML =
    `<div class="overlay"><form class="modal ${wide ? 'wide' : ''}"><h3>${esc(title)}</h3>${body}` +
    `<div class="error" hidden></div><div class="modal-foot"><button type="button" class="btn" data-close>Cancel</button>` +
    `<button class="btn primary">${esc(submit)}</button></div></form></div>`;
  const form = $('form', root), err = $('.error', root), btn = $('.btn.primary', root);
  const close = () => { root.innerHTML = ''; };
  $('[data-close]', root).onclick = close;
  $('.overlay', root).addEventListener('mousedown', e => { if (e.target.classList.contains('overlay')) close(); });
  if (onMount) onMount(form);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    err.hidden = true;
    btn.disabled = true;
    try { await onSubmit(form); close(); }
    catch (ex) { err.textContent = ex.message; err.hidden = false; }
    finally { btn.disabled = false; }
  });
  const first = form.querySelector('input,select');
  if (first) first.focus();
}

/* ------------------------------------------------------------------ login / session */
function showLogin(msg) {
  $('#app').hidden = true;
  $('#login').hidden = false;
  loadLoginBranches();
  const e = $('#loginError');
  e.hidden = !msg;
  e.textContent = msg || '';
}

function signOut() {
  history.replaceState(null, '', location.pathname);
  S.auth = null;
  S.me = null;
  try { sessionStorage.removeItem('auth'); } catch (e) { /* ignore */ }
  showLogin();
}

/* branch logo: initials on a colour derived from the Sacco ID */
function markHtml(name, code, small) {
  const words = String(name || '?').trim().split(/\s+/);
  const initials = (words.length > 1 ? words[0][0] + words[1][0] : words[0].slice(0, 2)).toUpperCase();
  let h = 0;
  for (const ch of String(code || name)) h = (h * 31 + ch.charCodeAt(0)) % 360;
  const shades = ['#1d4ed8', '#0a0a0a', '#1e3a8a', '#2563eb', '#0b2a6f'];
  return `<span class="mark${small ? ' sm' : ''}" style="background:${shades[h % shades.length]}">${esc(initials)}</span>`;
}

const HQ = 'HQ';
let loginBranches = [];

function brandLogin() {
  const v = $('#loginBranch').value;
  const b = loginBranches.find(x => String(x.id) === v);
  $('#loginMark').innerHTML = markHtml(b ? b.name : S.appName, b ? b.code : HQ);
  $('#loginTitle').textContent = b ? b.name : S.appName;
  $('#loginSub').textContent = b ? `Sign in to ${b.name}` : v === HQ ? 'Head office sign-in for the manager.'
    : oneBranchLogin() ? 'Sign in to continue.' : 'Choose your branch, then sign in.';
  document.title = b ? `${b.name} · Sign in` : S.appName;
  try { if (v) localStorage.setItem('lastBranch', v); } catch (e) { /* ignore */ }
}

async function loadConfig() {
  try {
    const cfg = await api('/api/public/config', { login: true });
    S.single = !!cfg.singleBranch;
    S.appName = cfg.appName || S.appName;
  } catch (e) { /* keep the defaults */ }
}

/** One branch and not the support address: there is no branch to choose, and signing in does not wait for the branch list. */
const oneBranchLogin = () => S.single && location.hash !== '#support';   // the support login is reached by adding #support to the address

async function loadLoginBranches() {
  const s = $('#loginBranch'), field = s.closest('label');
  await loadConfig();
  // the branch list is shown only when there is something to choose; it starts hidden, so "Loading branches…" never shows here
  const choose = !oneBranchLogin();
  field.hidden = !choose;
  s.required = choose;
  try {
    loginBranches = await api('/api/public/branches', { login: true });
    if (!choose) {
      s.innerHTML = loginBranches.map(b => `<option value="${b.id}">${esc(b.name)}</option>`).join('');
    } else {
      s.innerHTML = '<option value="">Select your branch…</option>' +
        `<option value="${HQ}">Head office (Manager)</option>` +
        loginBranches.map(b => `<option value="${b.id}">${esc(b.name)}</option>`).join('');
    }
    let last = ''; try { last = localStorage.getItem('lastBranch') || ''; } catch (e) { /* ignore */ }
    if (last && [...s.options].some(o => o.value === last)) s.value = last;
  } catch (err) {
    if (choose) s.innerHTML = '<option value="">Could not load branches</option>';
  }
  brandLogin();
}
$('#loginBranch').addEventListener('change', brandLogin);

$('#loginForm').addEventListener('submit', async e => {
  e.preventDefault();
  const f = new FormData(e.target);
  const btn = $('button', e.target);
  btn.disabled = true;
  S.auth = 'Basic ' + btoa(unescape(encodeURIComponent(`${f.get('username').trim()}:${f.get('password')}`)));
  try {
    S.me = await api('/api/me', { login: true });
    // The account must belong to the branch chosen on the form (one branch: every branch login belongs to it)
    const chosen = f.get('branch');
    if (oneBranchLogin()) {
      if (isMgr()) throw new Error('The manager signs in under Head office.');
    } else if (chosen === HQ) {
      if (!isMgr()) throw new Error('This is a branch account. Select your branch instead.');
    } else if (isMgr() || String(S.me.branchId) !== chosen) {
      const b = loginBranches.find(x => String(x.id) === chosen);
      throw new Error(isMgr() ? 'The manager signs in under Head office.' : `This account does not belong to ${b ? b.name : 'that branch'}.`);
    }
    try { sessionStorage.setItem('auth', S.auth); } catch (x) { /* ignore */ }
    $('input[name=password]', e.target).value = '';
    history.replaceState(null, '', location.pathname);   // always open on the dashboard after signing in
    start();
  } catch (err) {
    S.auth = null;
    S.me = null;
    showLogin(err.message.startsWith('Request failed') ? 'Cannot reach the server. Try again.' : err.message);
  } finally { btn.disabled = false; }
});

$('#logout').addEventListener('click', signOut);

/* the button beside the logo folds the menu down to its icons (names show on hover); this browser remembers the choice.
   quiet is for the first drawing after sign-in, when nothing should move. */
let sideMoveTimer = 0;
function foldSide(folded, quiet)
    {
        const app = $('#app');
        hideTip();
        // the fold button and the name beside the logo hide while they change place, then fade back in once the menu has mostly moved
        if(!quiet)
            {
                clearTimeout(sideMoveTimer);
                app.classList.add('side-moving');
                sideMoveTimer = setTimeout(() => app.classList.remove('side-moving'), 120);
            }
        app.classList.toggle('side-collapsed', folded);
        const t = $('#sideToggle');
        const tip = folded ? 'Show the full menu' : 'Fold the menu to icons';
        t.setAttribute('aria-pressed', String(folded));
        t.setAttribute('aria-label', tip);
        t.dataset.tip = tip;
        // the folded menu shows names with its own little label (showTip), so the browser's slow one is taken off
        document.querySelectorAll('#nav a, #nav .nav-drop-btn').forEach(el => el.dataset.tip = el.textContent.trim());
        [t, $('#meBtn')].forEach(b =>
            {
                if(folded || !b.dataset.tip) b.removeAttribute('title');
                else b.title = b.dataset.tip;
            });
        // a drop-down hidden by the fold can't be reached with the Tab key either
        document.querySelectorAll('#nav .nav-drop').forEach(d => openDrop(d, $('.nav-drop-btn', d).getAttribute('aria-expanded') === 'true'));
        try { localStorage.setItem('cpa.sideFolded', folded ? '1' : ''); } catch (e) { /* no storage: not remembered */ }
    }
$('#sideToggle').addEventListener('click', () => foldSide(!$('#app').classList.contains('side-collapsed')));

/* The names on the folded menu: a small black label beside the icon. The first one waits a moment,
   so it doesn't flash up as the mouse passes by; once one is showing, moving to the next icon swaps it over at once. */
const sideTip = document.createElement('div');
sideTip.className = 'side-tip';
sideTip.setAttribute('aria-hidden', 'true');   // the names are already read out from the menu itself
document.body.append(sideTip);
const canHover = matchMedia('(hover: hover) and (pointer: fine)');
let tipFor = null, tipWait = 0, tipWarmUntil = 0;

function showTip(el)
    {
        clearTimeout(tipWait);
        tipFor = el;
        const place = () =>
            {
                const r = el.getBoundingClientRect();
                sideTip.textContent = el.dataset.tip;
                sideTip.style.left = `${r.right + 10}px`;
                sideTip.style.top = `${r.top + r.height / 2}px`;
                sideTip.classList.add('show');
            };
        if(sideTip.classList.contains('show') || Date.now() < tipWarmUntil)
            {
                sideTip.classList.add('instant');
                place();
            }
        else
            {
                sideTip.classList.remove('instant');
                tipWait = setTimeout(place, 400);
            }
    }

function hideTip()
    {
        clearTimeout(tipWait);
        if(sideTip.classList.contains('show')) tipWarmUntil = Date.now() + 300;
        sideTip.classList.remove('show', 'instant');
        tipFor = null;
    }

const side = $('.side');
const tipTarget = node => $('#app').classList.contains('side-collapsed') && node instanceof Element ? node.closest('[data-tip]') : null;
side.addEventListener('mouseover', e =>
    {
        const el = tipTarget(e.target);
        if(el && el !== tipFor && canHover.matches) showTip(el);
    });
side.addEventListener('mouseout', e =>
    {
        if(tipFor && !tipFor.contains(e.relatedTarget)) hideTip();
    });
// the Tab key shows the name too, but a mouse click doesn't
side.addEventListener('focusin', e =>
    {
        const el = tipTarget(e.target);
        if(el && el.matches(':focus-visible')) showTip(el);
    });
side.addEventListener('focusout', () => hideTip());
side.addEventListener('pointerdown', () => hideTip());
$('#nav').addEventListener('scroll', () => hideTip());

/* the signed-in person at the foot of the menu opens a small menu (change password, sign out); any other click or Escape closes it */
function showMeMenu(open) {
  $('#meMenu').classList.toggle('open', open);
  $('#meBtn').setAttribute('aria-expanded', String(open));
}
$('#meBtn').addEventListener('click', e => { e.stopPropagation(); showMeMenu($('#meBtn').getAttribute('aria-expanded') !== 'true'); });
document.addEventListener('click', () => showMeMenu(false));
document.addEventListener('keydown', e => { if (e.key === 'Escape') showMeMenu(false); });

$('#chpw').addEventListener('click', () => modal({
  title: 'Change password', submit: 'Save password',
  body: inp('currentPassword', 'Current password', { type: 'password', req: 1 }) +
    inp('password', 'New password (min 8 characters)', { type: 'password', req: 1 }) +
    inp('confirm', 'Repeat the new password', { type: 'password', req: 1 }),
  onSubmit: async f => {
    const v = fd(f);
    if (v.password !== v.confirm) throw new Error('The two new passwords are not the same');
    await api('/api/me/password', { method: 'PUT', body: { currentPassword: v.currentPassword, password: v.password } });
    S.auth = 'Basic ' + btoa(unescape(encodeURIComponent(`${S.me.username}:${v.password}`)));
    try { sessionStorage.setItem('auth', S.auth); } catch (x) { /* ignore */ }
    toast('Password changed');
  }
}));

const REPORT_KIDS = [['reports/trial-balance', 'Trial balance'], ['reports/income-expenditure', 'Income & expenditure'],
  ['reports/balance-sheet', 'Balance sheet'], ['reports/member-statement', 'Member statement'], ['reports/member-summary', 'Member summary'],
  ['reports/savings-report', 'Savings report'], ['reports/shares-report', 'Shares report'], ['reports/loan-report', 'Loan report']];
const NAV_ADMIN = [
  ['dashboard', 'Dashboard'],
  ['f-accountsmenu', 'Accounts menu', [['reports/cashbook-receipts', 'Cashbook receipts'], ['reports/cashbook-payments', 'Cashbook payments'],
    ['journals', 'Journal entries'], ['accounts', 'Chart of accounts'], ['d-acreports', 'Accounts reports', [['gl', 'General ledger'], ...REPORT_KIDS]]]],
  ['f-savingscredit', 'Savings and credit menu', [['members', 'Member list'], ['deposits', 'Regular deposit'],
    ['loans', 'Loan applications'], ['withdrawals', 'Savings withdrawal']]],
  ['f-adminfunctions', 'Administrative functions', [['savingsinterest', 'Savings interest computation'], ['interest', 'Loan interest computation'],
    ['payroll', 'Payroll deductions'], ['payrollpaste', 'Payroll from Excel']]],
  ['f-accounting', 'Accounting', [['products', 'Products'], ['audit', 'Audit trail']]]
];
/* a branch admin also gets the staff screen (just before Accounting); treasurers and cashiers have the plain branch menu */
const ACC_AT = NAV_ADMIN.findIndex(([k]) => k === 'f-accounting');
const navFor = () => isMember() ? [['mystatement', 'My statement']]
  : isMgr() ? NAV_MANAGER
  : isBranchAdmin() ? [...NAV_ADMIN.slice(0, ACC_AT), ['f-staff', 'Administration', [['staff', 'Staff users'], ...(S.single ? [['momo', 'Mobile money numbers']] : []), ['messages', 'Messages']]], ...NAV_ADMIN.slice(ACC_AT)]
  : NAV_ADMIN;
const NAV_MANAGER = [
  ['dashboard', 'Dashboard'],
  ['f-admin', 'Administration', [['branches', 'Branches'], ['momo', 'Mobile money numbers'], ['yearcodes', 'New year codes'], ['admins', 'Users'], ['messages', 'Messages']]],
  ...NAV_ADMIN.slice(1)
];

/* menu icons: 24x24 line drawings, coloured by the stylesheet */
const ICONS = {
  dashboard: '<rect x="3" y="3" width="7" height="9" rx="1.5"/><rect x="14" y="3" width="7" height="5" rx="1.5"/><rect x="14" y="12" width="7" height="9" rx="1.5"/><rect x="3" y="16" width="7" height="5" rx="1.5"/>',
  branches: '<path d="M3 21h18"/><path d="M5 21V8l7-5 7 5v13"/><path d="M9 11h1.5M13.5 11H15M9 15h1.5M13.5 15H15"/><path d="M10.5 21v-3h3v3"/>',
  admins: '<path d="M12 3l8 3v6c0 4.8-3.3 7.9-8 9-4.7-1.1-8-4.2-8-9V6z"/><path d="M8.5 12l2.5 2.5 4.5-5"/>',
  members: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c0-3.6 2.9-6 6.5-6s6.5 2.4 6.5 6"/><circle cx="17.5" cy="9" r="2.5"/><path d="M17.5 14c2.7 0 4 1.9 4 5"/>',
  deposits: '<rect x="3" y="7" width="18" height="13" rx="2.5"/><path d="M3 11h18"/><path d="M12 2.5v6M9.5 6.5L12 9l2.5-2.5"/>',
  payrollpaste: '<rect x="5" y="4" width="14" height="17" rx="2"/><path d="M9 4V3h6v1M8.5 10h7M8.5 14h7M8.5 17.5h4"/>',
  payroll: '<rect x="2.5" y="6.5" width="19" height="11" rx="2"/><circle cx="12" cy="12" r="2.6"/><path d="M6 10v.01M18 14v.01"/>',
  interest: '<rect x="3" y="4" width="18" height="17" rx="2"/><path d="M3 9h18M8 2v4M16 2v4"/><path d="M9.5 17.5l5-5"/><circle cx="9.5" cy="12.5" r=".9"/><circle cx="14.5" cy="17.5" r=".9"/>',
  loans: '<circle cx="12" cy="12" r="9"/><path d="M8.5 15.5l7-7"/><circle cx="9" cy="9" r="1"/><circle cx="15" cy="15" r="1"/>',
  accounts: '<path d="M8 6h13M8 12h13M8 18h13"/><path d="M3.5 6h.01M3.5 12h.01M3.5 18h.01"/>',
  products: '<path d="M21 8l-9-5-9 5v8l9 5 9-5z"/><path d="M3 8l9 5 9-5M12 13v8"/>',
  gl: '<path d="M2.5 5.5H9a3 3 0 0 1 3 3V21a2.5 2.5 0 0 0-2.5-2.5h-7z"/><path d="M21.5 5.5H15a3 3 0 0 0-3 3V21a2.5 2.5 0 0 1 2.5-2.5h7z"/>',
  journals: '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/>',
  audit: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/>',
  'reports/trial-balance': '<path d="M12 3v18M7 21h10"/><path d="M4 7h16"/><path d="M6.5 7L3.5 14a3.2 3.2 0 0 0 6 0zM17.5 7l-3 7a3.2 3.2 0 0 0 6 0z"/>',
  'reports/income-expenditure': '<path d="M3 17l6-6 4 4 8-8"/><path d="M15 7h6v6"/>',
  'reports/balance-sheet': '<rect x="3" y="4" width="18" height="16" rx="2"/><path d="M12 4v16M3 10h18"/>',
  'reports/member-statement': '<path d="M14 3H6.5A2 2 0 0 0 4.5 5v14a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V8.5z"/><path d="M14 3v5.5h5.5M8 13h8M8 17h5"/>'
};
ICONS.staff = ICONS.admins;
ICONS.yearcodes = '<rect x="3" y="4.5" width="18" height="16" rx="2"/><path d="M3 9.5h18M8 3v3M16 3v3M8 14h3M13 14h3M8 17h3"/>';
ICONS.momo = '<rect x="7" y="2.5" width="10" height="19" rx="2"/><path d="M11 18.5h2M10 6h4"/>';
ICONS.messages = '<path d="M4 5h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9l-5 4V6a1 1 0 0 1 1-1z"/><path d="M8 9.5h8M8 13h5"/>';
ICONS.withdrawals = '<rect x="3" y="7" width="18" height="12" rx="2"/><path d="M12 16V4m0 0l-3.5 3.5M12 4l3.5 3.5"/><circle cx="12" cy="13" r="1.6"/>';
ICONS.mystatement = ICONS['reports/member-statement'];
ICONS['reports/member-summary'] = ICONS.members;
ICONS['reports/savings-report'] = ICONS.deposits;
ICONS['reports/shares-report'] = '<path d="M12 3v9h9a9 9 0 1 1-9-9z"/><path d="M15 3.5a9 9 0 0 1 5.5 5.5H15z"/>';
ICONS['reports/loan-report'] = ICONS.loans;
ICONS['d-acreports'] = '<path d="M4 20V10M10 20V4M16 20v-7M22 20H2"/>';
/* savings interest: a stack of coins with an arrow up (loan interest keeps the calendar) */
ICONS.savingsinterest = '<ellipse cx="9" cy="6" rx="6" ry="2.5"/><path d="M3 6v4.5c0 1.4 2.7 2.5 6 2.5s6-1.1 6-2.5V6"/><path d="M3 10.5V15c0 1.4 2.7 2.5 6 2.5"/><path d="M18 21v-8M15 16l3-3 3 3"/>';
ICONS['reports/cashbook-receipts'] = '<path d="M4 19.5V5a2 2 0 0 1 2-2h14v15H6a2 2 0 0 0-2 2 2 2 0 0 0 2 2h14v-4"/><path d="M12 6.5v7M9 10.5l3 3 3-3"/>';
ICONS['reports/cashbook-payments'] = '<path d="M4 19.5V5a2 2 0 0 1 2-2h14v15H6a2 2 0 0 0-2 2 2 2 0 0 0 2 2h14v-4"/><path d="M12 13.5v-7M9 9.5l3-3 3 3"/>';
/* sidebar icons: Iconsax (MIT licence, from iconsax-react 0.0.8), the set the reference design uses.
   Each has two drawings: [outline, filled]; the filled one marks the open screen. */
const ISX = {
  ArrowDown2: ['<path stroke="currentColor" stroke-linecap="round" stroke-linejoin="round" stroke-miterlimit="10" stroke-width="1.5" d="M19.92 8.95l-6.52 6.52c-.77.77-2.03.77-2.8 0L4.08 8.95"/>',
    '<path fill="currentColor" d="M17.919 8.18H6.079c-.96 0-1.44 1.16-.76 1.84l5.18 5.18c.83.83 2.18.83 3.01 0l1.97-1.97 3.21-3.21c.67-.68.19-1.84-.77-1.84z"/>'],
  Book1: ['<path d="M22 16.74V4.67c0-1.2-.98-2.09-2.17-1.99h-.06c-2.1.18-5.29 1.25-7.07 2.37l-.17.11c-.29.18-.77.18-1.06 0l-.25-.15C9.44 3.9 6.26 2.84 4.16 2.67 2.97 2.57 2 3.47 2 4.66v12.08c0 .96.78 1.86 1.74 1.98l.29.04c2.17.29 5.52 1.39 7.44 2.44l.04.02c.27.15.7.15.96 0 1.92-1.06 5.28-2.17 7.46-2.46l.33-.04c.96-.12 1.74-1.02 1.74-1.98ZM12 5.49v15M7.75 8.49H5.5M8.5 11.49h-3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>',
    '<path d="M22 4.85v11.89c0 .97-.79 1.86-1.76 1.98l-.31.04c-1.64.22-3.95.9-5.81 1.68-.65.27-1.37-.22-1.37-.93V5.6c0-.37.21-.71.54-.89 1.83-.99 4.6-1.87 6.48-2.03h.06c1.2 0 2.17.97 2.17 2.17ZM10.708 4.71c-1.83-.99-4.6-1.87-6.48-2.03h-.07c-1.2 0-2.17.97-2.17 2.17v11.89c0 .97.79 1.86 1.76 1.98l.31.04c1.64.22 3.95.9 5.81 1.68.65.27 1.37-.22 1.37-.93V5.6a1 1 0 0 0-.53-.89Zm-5.71 3.03h2.25a.749.749 0 1 1 0 1.5h-2.25a.749.749 0 1 1 0-1.5Zm3 4.5h-3a.749.749 0 1 1 0-1.5h3a.749.749 0 1 1 0 1.5Z" fill="currentColor"/>'],
  Box: ['<path d="M3.17 7.44 12 12.55l8.77-5.08M12 21.61v-9.07" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M9.93 2.48 4.59 5.45c-1.21.67-2.2 2.35-2.2 3.73v5.65c0 1.38.99 3.06 2.2 3.73l5.34 2.97c1.14.63 3.01.63 4.15 0l5.34-2.97c1.21-.67 2.2-2.35 2.2-3.73V9.18c0-1.38-.99-3.06-2.2-3.73l-5.34-2.97c-1.15-.64-3.01-.64-4.15 0Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M17 13.24V9.58L7.51 4.1" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>',
    '<path d="m20.208 7.821-7.7 4.46c-.31.18-.7.18-1.02 0l-7.7-4.46c-.55-.32-.69-1.07-.27-1.54.29-.33.62-.6.97-.79l5.42-3c1.16-.65 3.04-.65 4.2 0l5.42 3c.35.19.68.47.97.79.4.47.26 1.22-.29 1.54ZM11.43 14.139v6.82c0 .76-.77 1.26-1.45.93-2.06-1.01-5.53-2.9-5.53-2.9-1.22-.69-2.22-2.43-2.22-3.86v-5.16c0-.79.83-1.29 1.51-.9l7.19 4.17c.3.19.5.53.5.9ZM12.57 14.139v6.82c0 .76.77 1.26 1.45.93 2.06-1.01 5.53-2.9 5.53-2.9 1.22-.69 2.22-2.43 2.22-3.86v-5.16c0-.79-.83-1.29-1.51-.9l-7.19 4.17c-.3.19-.5.53-.5.9Z" fill="currentColor"/>'],
  Building: ['<path d="M12.5 22H4.08c-1.16 0-2.11-.93-2.11-2.07V5.09c0-2.62 1.95-3.81 4.34-2.64l4.44 2.18c.96.47 1.75 1.72 1.75 2.78V22ZM21.97 15.06v3.78c0 2.16-1 3.16-3.16 3.16H12.5V10.42l.47.1 4.5 1.01 2.03.45c1.32.29 2.4.97 2.46 2.89.01.06.01.12.01.19ZM5.5 9h3.47M5.5 13h3.47" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M17.47 11.53v3.22c0 1.24-1.01 2.25-2.25 2.25s-2.25-1.01-2.25-2.25v-4.23l4.5 1.01ZM21.96 14.87A2.249 2.249 0 0 1 19.72 17c-1.24 0-2.25-1.01-2.25-2.25v-3.22l2.03.45c1.32.29 2.4.97 2.46 2.89Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>',
    '<path d="M10.75 4.64 6.32 2.45c-2.39-1.17-4.35.02-4.35 2.64v14.84c0 1.14.95 2.07 2.11 2.07h7.42c.55 0 1-.45 1-1V7.41c0-1.05-.79-2.3-1.75-2.77Zm-1.78 9.11H5.5c-.41 0-.75-.34-.75-.75s.34-.75.75-.75h3.47a.749.749 0 1 1 0 1.5Zm0-4H5.5c-.41 0-.75-.34-.75-.75s.34-.75.75-.75h3.47a.749.749 0 1 1 0 1.5ZM22 18.04v1.46a2.5 2.5 0 0 1-2.5 2.5h-4.53c-.54 0-.97-.43-.97-.97v-2.16c1.07.13 2.2-.18 3.01-.83.68.55 1.55.88 2.5.88.93 0 1.8-.33 2.49-.88ZM22 15.05v.01a2.5 2.5 0 0 1-2.49 2.36 2.5 2.5 0 0 1-2.5-2.5c0 1.53-1.41 2.76-3.01 2.45V12c0-.64.59-1.12 1.22-.98l1.79.4.48.11 2.04.46c.49.1.94.27 1.33.52 0 .01.01.01.01.01.1.07.2.15.29.24.46.46.76 1.13.83 2.11 0 .06.01.12.01.18Z" fill="currentColor"/>'],
  Calendar2: ['<path d="M8 2v3M16 2v3M3.5 9.09h17M21 8.5V17c0 3-1.5 5-5 5H8c-3.5 0-5-2-5-5V8.5c0-3 1.5-5 5-5h8c3.5 0 5 2 5 5Z" stroke="currentColor" stroke-width="1.5" stroke-miterlimit="10" stroke-linecap="round" stroke-linejoin="round"/><path d="M11.995 13.7h.01M8.294 13.7h.01M8.294 16.7h.01" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    '<path d="M16.75 3.56V2c0-.41-.34-.75-.75-.75s-.75.34-.75.75v1.5h-6.5V2c0-.41-.34-.75-.75-.75s-.75.34-.75.75v1.56c-2.7.25-4.01 1.86-4.21 4.25-.02.29.22.53.5.53h16.92c.29 0 .53-.25.5-.53-.2-2.39-1.51-4-4.21-4.25ZM20 9.84H4c-.55 0-1 .45-1 1V17c0 3 1.5 5 5 5h8c3.5 0 5-2 5-5v-6.16c0-.55-.45-1-1-1ZM9.21 18.21c-.1.09-.21.16-.33.21-.12.05-.25.08-.38.08s-.26-.03-.38-.08-.23-.12-.33-.21c-.18-.19-.29-.45-.29-.71 0-.26.11-.52.29-.71.1-.09.21-.16.33-.21a1 1 0 0 1 .76 0c.12.05.23.12.33.21.18.19.29.45.29.71 0 .26-.11.52-.29.71Zm.21-3.83c-.05.12-.12.23-.21.33-.1.09-.21.16-.33.21-.12.05-.25.08-.38.08s-.26-.03-.38-.08-.23-.12-.33-.21c-.09-.1-.16-.21-.21-.33A.995.995 0 0 1 7.5 14c0-.13.03-.26.08-.38s.12-.23.21-.33c.1-.09.21-.16.33-.21a1 1 0 0 1 .76 0c.12.05.23.12.33.21.09.1.16.21.21.33.05.12.08.25.08.38s-.03.26-.08.38Zm3.29.33c-.1.09-.21.16-.33.21-.12.05-.25.08-.38.08s-.26-.03-.38-.08-.23-.12-.33-.21c-.18-.19-.29-.45-.29-.71 0-.26.11-.52.29-.71.1-.09.21-.16.33-.21.24-.11.52-.11.76 0 .12.05.23.12.33.21.18.19.29.45.29.71 0 .26-.11.52-.29.71Z" fill="currentColor"/>'],
  ChartSquare: ['<path d="M10.11 11.15H7.46c-.63 0-1.14.51-1.14 1.14v5.12h3.79v-6.26 0Z" stroke="currentColor" stroke-width="1.5" stroke-miterlimit="10" stroke-linecap="round" stroke-linejoin="round"/><path d="M12.761 6.6h-1.52c-.63 0-1.14.51-1.14 1.14v9.66h3.79V7.74c0-.63-.5-1.14-1.13-1.14ZM16.548 12.85h-2.65v4.55h3.79v-3.41c-.01-.63-.52-1.14-1.14-1.14Z" stroke="currentColor" stroke-width="1.5" stroke-miterlimit="10" stroke-linecap="round" stroke-linejoin="round"/><path d="M9 22h6c5 0 7-2 7-7V9c0-5-2-7-7-7H9C4 2 2 4 2 9v6c0 5 2 7 7 7Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>',
    '<path d="M16.19 2H7.81C4.17 2 2 4.17 2 7.81v8.37C2 19.83 4.17 22 7.81 22h8.37c3.64 0 5.81-2.17 5.81-5.81V7.81C22 4.17 19.83 2 16.19 2ZM9.11 16.9c0 .28-.22.5-.5.5H5.82c-.28 0-.5-.22-.5-.5v-4.62c0-.63.51-1.14 1.14-1.14h2.15c.28 0 .5.22.5.5v5.26Zm4.78 0c0 .28-.22.5-.5.5H10.6c-.28 0-.5-.22-.5-.5V7.74c0-.63.51-1.14 1.14-1.14h1.52c.63 0 1.14.51 1.14 1.14v9.16h-.01Zm4.79 0c0 .28-.22.5-.5.5h-2.79c-.28 0-.5-.22-.5-.5v-3.55c0-.28.22-.5.5-.5h2.15c.63 0 1.14.51 1.14 1.14v2.91Z" fill="currentColor"/>'],
  Clock: ['<path d="M22 12c0 5.52-4.48 10-10 10S2 17.52 2 12 6.48 2 12 2s10 4.48 10 10Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><path d="m15.71 15.18-3.1-1.85c-.54-.32-.98-1.09-.98-1.72v-4.1" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>',
    '<path d="M12 2C6.49 2 2 6.49 2 12s4.49 10 10 10 10-4.49 10-10S17.51 2 12 2Zm4.35 13.57a.746.746 0 0 1-1.03.26l-3.1-1.85c-.77-.46-1.34-1.47-1.34-2.36v-4.1c0-.41.34-.75.75-.75s.75.34.75.75v4.1c0 .36.3.89.61 1.07l3.1 1.85c.36.21.48.67.26 1.03Z" fill="currentColor"/>'],
  DocumentText: ['<path d="M21 7v10c0 3-1.5 5-5 5H8c-3.5 0-5-2-5-5V7c0-3 1.5-5 5-5h8c3.5 0 5 2 5 5Z" stroke="currentColor" stroke-width="1.5" stroke-miterlimit="10" stroke-linecap="round" stroke-linejoin="round"/><path d="M14.5 4.5v2c0 1.1.9 2 2 2h2M8 13h4M8 17h8" stroke="currentColor" stroke-width="1.5" stroke-miterlimit="10" stroke-linecap="round" stroke-linejoin="round"/>',
    '<path d="M15.8 2.21c-.41-.41-1.12-.13-1.12.44v3.49c0 1.46 1.24 2.67 2.75 2.67.95.01 2.27.01 3.4.01.57 0 .87-.67.47-1.07-1.44-1.45-4.02-4.06-5.5-5.54Z" fill="currentColor"/><path d="M20.5 10.19h-2.89c-2.37 0-4.3-1.93-4.3-4.3V3c0-.55-.45-1-1-1H8.07C4.99 2 2.5 4 2.5 7.57v8.86C2.5 20 4.99 22 8.07 22h7.86c3.08 0 5.57-2 5.57-5.57v-5.24c0-.55-.45-1-1-1Zm-9 7.56h-4c-.41 0-.75-.34-.75-.75s.34-.75.75-.75h4c.41 0 .75.34.75.75s-.34.75-.75.75Zm2-4h-6c-.41 0-.75-.34-.75-.75s.34-.75.75-.75h6c.41 0 .75.34.75.75s-.34.75-.75.75Z" fill="currentColor"/>'],
  DocumentUpload: ['<path d="M9 17v-6l-2 2M9 11l2 2" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M22 10v5c0 5-2 7-7 7H9c-5 0-7-2-7-7V9c0-5 2-7 7-7h5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M22 10h-4c-3 0-4-1-4-4V2l8 8Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>',
    '<path d="M20.5 10.19h-2.89c-2.37 0-4.3-1.93-4.3-4.3V3c0-.55-.45-1-1-1H8.07C4.99 2 2.5 4 2.5 7.57v8.86C2.5 20 4.99 22 8.07 22h7.86c3.08 0 5.57-2 5.57-5.57v-5.24c0-.55-.45-1-1-1Zm-8.97 3.34c-.15.15-.34.22-.53.22s-.38-.07-.53-.22l-.72-.72V17c0 .41-.34.75-.75.75s-.75-.34-.75-.75v-4.19l-.72.72c-.29.29-.77.29-1.06 0a.754.754 0 0 1 0-1.06l2-2c.07-.06.14-.11.22-.15.02-.01.05-.02.07-.03.06-.02.12-.03.19-.04h.08c.08 0 .16.02.24.05h.02c.08.03.16.09.22.15.01.01.02.01.02.02l2 2c.29.29.29.77 0 1.06Z" fill="currentColor"/><path d="M17.43 8.81c.95.01 2.27.01 3.4.01.57 0 .87-.67.47-1.07-1.44-1.45-4.02-4.06-5.5-5.54-.41-.41-1.12-.13-1.12.44v3.49c0 1.46 1.24 2.67 2.75 2.67Z" fill="currentColor"/>'],
  Home2: ['<path d="m9.02 2.84-5.39 4.2C2.73 7.74 2 9.23 2 10.36v7.41c0 2.32 1.89 4.22 4.21 4.22h11.58c2.32 0 4.21-1.9 4.21-4.21V10.5c0-1.21-.81-2.76-1.8-3.45l-6.18-4.33c-1.4-.98-3.65-.93-5 .12ZM12 17.99v-3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>',
    '<path d="m20.04 6.822-5.76-4.03c-1.57-1.1-3.98-1.04-5.49.13l-5.01 3.91c-1 .78-1.79 2.38-1.79 3.64v6.9c0 2.55 2.07 4.63 4.62 4.63h10.78c2.55 0 4.62-2.07 4.62-4.62v-6.78c0-1.35-.87-3.01-1.97-3.78Zm-7.29 11.18c0 .41-.34.75-.75.75s-.75-.34-.75-.75v-3c0-.41.34-.75.75-.75s.75.34.75.75v3Z" fill="currentColor"/>'],
  Message: ['<path d="M8.5 19H8c-4 0-6-1-6-6V8c0-4 2-6 6-6h8c4 0 6 2 6 6v5c0 4-2 6-6 6h-.5c-.31 0-.61.15-.8.4l-1.5 2c-.66.88-1.74.88-2.4 0l-1.5-2c-.16-.22-.53-.4-.8-.4Z" stroke="currentColor" stroke-width="1.5" stroke-miterlimit="10" stroke-linecap="round" stroke-linejoin="round"/><path d="M15.996 11h.01M11.995 11h.01M7.995 11h.008" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>',
    '<path d="M17 2H7C4.24 2 2 4.23 2 6.98v6.98c0 2.75 2.24 4.98 5 4.98h1.5c.27 0 .63.18.8.4l1.5 1.99c.66.88 1.74.88 2.4 0l1.5-1.99c.19-.25.49-.4.8-.4H17c2.76 0 5-2.23 5-4.98V6.98C22 4.23 19.76 2 17 2ZM8 12c-.56 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.44 1-1 1Zm4 0c-.56 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.44 1-1 1Zm4 0c-.56 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.44 1-1 1Z" fill="currentColor"/>'],
  Mobile: ['<path d="M17 6v10c0 4-1 5-5 5H6c-4 0-5-1-5-5V6c0-4 1-5 5-5h6c4 0 5 1 5 5ZM11 4.5H7" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M9 18.1A1.55 1.55 0 1 0 9 15a1.55 1.55 0 0 0 0 3.1Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>',
    '<path d="M16.24 2H7.76C5 2 4 3 4 5.81v12.38C4 21 5 22 7.76 22h8.47C19 22 20 21 20 18.19V5.81C20 3 19 2 16.24 2ZM12 19.3c-.96 0-1.75-.79-1.75-1.75s.79-1.75 1.75-1.75 1.75.79 1.75 1.75-.79 1.75-1.75 1.75Zm2-13.05h-4c-.41 0-.75-.34-.75-.75s.34-.75.75-.75h4c.41 0 .75.34.75.75s-.34.75-.75.75Z" fill="currentColor"/>'],
  MoneyRecive: ['<path d="M9.5 13.75c0 .97.75 1.75 1.67 1.75h1.88c.8 0 1.45-.68 1.45-1.53 0-.91-.4-1.24-.99-1.45l-3.01-1.05c-.59-.21-.99-.53-.99-1.45 0-.84.65-1.53 1.45-1.53h1.88c.92 0 1.67.78 1.67 1.75M12 7.5v9" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M22 12c0 5.52-4.48 10-10 10S2 17.52 2 12 6.48 2 12 2" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M17 3v4h4M22 2l-5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>',
    '<path d="M18.809 6.25h1.36c-.19-.27-.39-.52-.6-.77l-.76.77ZM18.52 4.42c-.25-.21-.5-.41-.77-.6v1.36l.77-.76ZM19.58 5.481l2.95-2.95c.29-.29.29-.77 0-1.06a.754.754 0 0 0-1.06 0l-2.95 2.95c.38.33.73.69 1.06 1.06ZM17.752 3c0-.41-.34-.75-.75-.75-.4 0-.72.32-.74.71.52.25 1.02.53 1.49.86V3ZM21.752 7c0-.41-.34-.75-.75-.75h-.83c.33.47.62.97.86 1.49.4-.02.72-.34.72-.74ZM12.75 14.75h.3c.39 0 .7-.35.7-.78 0-.54-.15-.62-.49-.74l-.51-.18v1.7Z" fill="currentColor"/><path d="M21.04 7.74c-.01 0-.02.01-.04.01h-4c-.1 0-.19-.02-.29-.06a.782.782 0 0 1-.41-.41.868.868 0 0 1-.05-.28V3c0-.01.01-.02.01-.04C14.96 2.35 13.52 2 12 2 6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10c0-1.52-.35-2.96-.96-4.26Zm-7.29 4.08c.64.22 1.5.69 1.5 2.16 0 1.25-.99 2.28-2.2 2.28h-.3v.25c0 .41-.34.75-.75.75s-.75-.34-.75-.75v-.25h-.08c-1.33 0-2.42-1.12-2.42-2.5 0-.42.34-.76.75-.76s.75.34.75.75c0 .55.41 1 .92 1h.08v-2.22l-1-.35c-.64-.22-1.5-.69-1.5-2.16 0-1.25.99-2.28 2.2-2.28h.3V7.5c0-.41.34-.75.75-.75s.75.34.75.75v.25h.08c1.33 0 2.42 1.12 2.42 2.5 0 .41-.34.75-.75.75s-.75-.34-.75-.75c0-.55-.41-1-.92-1h-.08v2.22l1 .35Z" fill="currentColor"/><path d="M10.25 10.03c0 .54.15.62.49.74l.51.18v-1.7h-.3c-.38 0-.7.35-.7.78Z" fill="currentColor"/>'],
  MoneySend: ['<path d="M9.5 13.75c0 .97.75 1.75 1.67 1.75h1.88c.8 0 1.45-.68 1.45-1.53 0-.91-.4-1.24-.99-1.45l-3.01-1.05c-.59-.21-.99-.53-.99-1.45 0-.84.65-1.53 1.45-1.53h1.88c.92 0 1.67.78 1.67 1.75M12 7.5v9" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M22 12c0 5.52-4.48 10-10 10S2 17.52 2 12 6.48 2 12 2M22 6V2h-4M17 7l5-5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>',
    '<path d="M10.25 10.03c0 .54.15.62.49.74l.51.18v-1.7h-.3c-.38 0-.7.35-.7.78ZM12.75 14.75h.3c.39 0 .7-.35.7-.78 0-.54-.15-.62-.49-.74l-.51-.18v1.7Z" fill="currentColor"/><path d="m19.58 5.48-2.05 2.05c-.15.15-.34.22-.53.22s-.38-.07-.53-.22a.754.754 0 0 1 0-1.06l2.05-2.05C16.76 2.92 14.49 2 12 2 6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10c0-2.49-.92-4.76-2.42-6.52Zm-5.83 6.34c.64.23 1.5.69 1.5 2.16 0 1.25-.99 2.28-2.2 2.28h-.3v.25c0 .41-.34.75-.75.75s-.75-.34-.75-.75v-.25h-.08c-1.33 0-2.42-1.12-2.42-2.5 0-.42.34-.76.75-.76s.75.34.75.75c0 .55.41 1 .92 1h.08v-2.22l-1-.35c-.64-.23-1.5-.69-1.5-2.16 0-1.25.99-2.28 2.2-2.28h.3V7.5c0-.41.34-.75.75-.75s.75.34.75.75v.25h.08c1.33 0 2.42 1.12 2.42 2.5 0 .41-.34.75-.75.75s-.75-.34-.75-.75c0-.55-.41-1-.92-1h-.08v2.22l1 .35ZM22.69 1.71a.782.782 0 0 0-.41-.41.868.868 0 0 0-.28-.05h-4c-.41 0-.75.34-.75.75s.34.75.75.75h2.19l-1.67 1.67c.38.33.73.68 1.06 1.06l1.67-1.67V6c0 .41.34.75.75.75s.75-.34.75-.75V2c0-.1-.02-.19-.06-.29Z" fill="currentColor"/>'],
  MoneyTime: ['<path d="M12 14.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5ZM18.5 9.5v5M5 22a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z" stroke="currentColor" stroke-width="1.5" stroke-miterlimit="10" stroke-linecap="round" stroke-linejoin="round"/><path d="M5.25 16.75v.93c0 .35-.18.68-.49.86L4 19" stroke="currentColor" stroke-width="1.5" stroke-miterlimit="10" stroke-linecap="round" stroke-linejoin="round"/><path d="M2 15.2V9c0-3.5 2-5 5-5h10c3 0 5 1.5 5 5v6c0 3.5-2 5-5 5H8.5" stroke="currentColor" stroke-width="1.5" stroke-miterlimit="10" stroke-linecap="round" stroke-linejoin="round"/>',
    '<path d="M5 14c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4Zm1 3.68c0 .61-.33 1.19-.85 1.5l-.76.46c-.12.08-.26.11-.39.11-.25 0-.5-.13-.64-.36-.21-.35-.1-.82.26-1.03l.76-.46c.07-.04.12-.13.12-.21v-.93c0-.42.34-.76.75-.76s.75.34.75.75v.93Z" fill="currentColor"/><path d="M17 4H7C4 4 2 5.5 2 9v3.56c0 .37.38.6.71.45.98-.45 2.11-.62 3.3-.41 2.63.47 4.56 2.91 4.49 5.58-.01.42-.07.83-.18 1.23-.08.31.17.6.49.6H17c3 0 5-1.5 5-5V9c0-3.5-2-5-5-5Zm-5 10.5a2.5 2.5 0 0 1 0-5 2.5 2.5 0 0 1 0 5Zm7.25-.5c0 .41-.34.75-.75.75s-.75-.34-.75-.75v-4c0-.41.34-.75.75-.75s.75.34.75.75v4Z" fill="currentColor"/>'],
  PercentageSquare: ['<path d="M9 2h6c5 0 7 2 7 7v6c0 5-2 7-7 7H9c-5 0-7-2-7-7V9c0-5 2-7 7-7ZM8.57 15.27l6.54-6.54" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M8.98 10.37a1.23 1.23 0 1 0 0-2.46 1.23 1.23 0 0 0 0 2.46ZM15.52 16.09a1.23 1.23 0 1 0 0-2.46 1.23 1.23 0 0 0 0 2.46Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>',
    '<path d="M16.19 2H7.81C4.17 2 2 4.17 2 7.81v8.37C2 19.83 4.17 22 7.81 22h8.37c3.64 0 5.81-2.17 5.81-5.81V7.81C22 4.17 19.83 2 16.19 2ZM7.75 9.14c0-.81.66-1.48 1.48-1.48.81 0 1.48.66 1.48 1.48 0 .81-.66 1.48-1.48 1.48a1.49 1.49 0 0 1-1.48-1.48Zm1.07 6.88c-.19 0-.38-.07-.53-.22a.754.754 0 0 1 0-1.06l6.55-6.55c.29-.29.77-.29 1.06 0 .29.29.29.77 0 1.06L9.35 15.8c-.15.15-.34.22-.53.22Zm6.95.32c-.81 0-1.48-.66-1.48-1.48 0-.81.66-1.48 1.48-1.48.81 0 1.48.66 1.48 1.48s-.66 1.48-1.48 1.48Z" fill="currentColor"/>'],
  Profile2User: ['<path d="M9.16 10.87c-.1-.01-.22-.01-.33 0a4.42 4.42 0 0 1-4.27-4.43C4.56 3.99 6.54 2 9 2a4.435 4.435 0 0 1 .16 8.87ZM16.41 4c1.94 0 3.5 1.57 3.5 3.5 0 1.89-1.5 3.43-3.37 3.5a1.13 1.13 0 0 0-.26 0M4.16 14.56c-2.42 1.62-2.42 4.26 0 5.87 2.75 1.84 7.26 1.84 10.01 0 2.42-1.62 2.42-4.26 0-5.87-2.74-1.83-7.25-1.83-10.01 0ZM18.34 20c.72-.15 1.4-.44 1.96-.87 1.56-1.17 1.56-3.1 0-4.27-.55-.42-1.22-.7-1.93-.86" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>',
    '<path d="M9 2C6.38 2 4.25 4.13 4.25 6.75c0 2.57 2.01 4.65 4.63 4.74.08-.01.16-.01.22 0h.07a4.738 4.738 0 0 0 4.58-4.74C13.75 4.13 11.62 2 9 2ZM14.08 14.149c-2.79-1.86-7.34-1.86-10.15 0-1.27.85-1.97 2-1.97 3.23s.7 2.37 1.96 3.21c1.4.94 3.24 1.41 5.08 1.41 1.84 0 3.68-.47 5.08-1.41 1.26-.85 1.96-1.99 1.96-3.23-.01-1.23-.7-2.37-1.96-3.21ZM19.99 7.338c.16 1.94-1.22 3.64-3.13 3.87h-.05c-.06 0-.12 0-.17.02-.97.05-1.86-.26-2.53-.83 1.03-.92 1.62-2.3 1.5-3.8a4.64 4.64 0 0 0-.77-2.18 3.592 3.592 0 0 1 5.15 2.92Z" fill="currentColor"/><path d="M21.988 16.59c-.08.97-.7 1.81-1.74 2.38-1 .55-2.26.81-3.51.78.72-.65 1.14-1.46 1.22-2.32.1-1.24-.49-2.43-1.67-3.38-.67-.53-1.45-.95-2.3-1.26 2.21-.64 4.99-.21 6.7 1.17.92.74 1.39 1.67 1.3 2.63Z" fill="currentColor"/>'],
  ReceiptMinus: ['<path d="M22 6v2.42C22 10 21 11 19.42 11H16V4.01C16 2.9 16.91 2 18.02 2c1.09.01 2.09.45 2.81 1.17C21.55 3.9 22 4.9 22 6Z" stroke="currentColor" stroke-width="1.5" stroke-miterlimit="10" stroke-linecap="round" stroke-linejoin="round"/><path d="M2 7v14c0 .83.94 1.3 1.6.8l1.71-1.28c.4-.3.96-.26 1.32.1l1.66 1.67c.39.39 1.03.39 1.42 0l1.68-1.68c.35-.35.91-.39 1.3-.09l1.71 1.28c.66.49 1.6.02 1.6-.8V4c0-1.1.9-2 2-2H6C3 2 2 3.79 2 6v1Z" stroke="currentColor" stroke-width="1.5" stroke-miterlimit="10" stroke-linecap="round" stroke-linejoin="round"/><path d="M6.25 10h5.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>',
    '<path d="M7 2H6C3 2 2 3.79 2 6v15c0 .83.94 1.3 1.6.8l1.71-1.28c.4-.3.96-.26 1.32.1l1.66 1.67c.39.39 1.03.39 1.42 0l1.68-1.68c.35-.35.91-.39 1.3-.09l1.71 1.28c.66.49 1.6.02 1.6-.8V4c0-1.1.9-2 2-2H7Zm4.75 8.75h-5.5c-.41 0-.75-.34-.75-.75s.34-.75.75-.75h5.5c.41 0 .75.34.75.75s-.34.75-.75.75Z" fill="currentColor"/><path d="M18.01 2v1.5c.66 0 1.29.27 1.75.72.48.49.74 1.12.74 1.78v2.42c0 .74-.33 1.08-1.08 1.08H17.5V4.01c0-.28.23-.51.51-.51V2Zm0 0C16.9 2 16 2.9 16 4.01V11h3.42C21 11 22 10 22 8.42V6c0-1.1-.45-2.1-1.17-2.83A4.044 4.044 0 0 0 18.01 2c.01 0 0 0 0 0Z" fill="currentColor"/>'],
  SecurityUser: ['<path d="M10.49 2.23 5.5 4.1c-1.15.43-2.09 1.79-2.09 3.02v7.43c0 1.18.78 2.73 1.73 3.44l4.3 3.21c1.41 1.06 3.73 1.06 5.14 0l4.3-3.21c.95-.71 1.73-2.26 1.73-3.44V7.12c0-1.23-.94-2.59-2.09-3.02l-4.99-1.87c-.85-.31-2.21-.31-3.04 0Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M12 10.92h-.13c-.94-.03-1.69-.81-1.69-1.76 0-.97.79-1.76 1.76-1.76s1.76.79 1.76 1.76c-.01.96-.76 1.73-1.7 1.76ZM10.01 13.72c-.96.64-.96 1.69 0 2.33 1.09.73 2.88.73 3.97 0 .96-.64.96-1.69 0-2.33-1.08-.73-2.87-.73-3.97 0Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>',
    '<path d="m18.5 4.11-4.99-1.87c-.83-.31-2.19-.31-3.02 0L5.5 4.11c-1.15.43-2.09 1.79-2.09 3.01v7.43c0 1.18.78 2.73 1.73 3.44l4.3 3.21c1.41 1.06 3.73 1.06 5.14 0l4.3-3.21c.95-.71 1.73-2.26 1.73-3.44V7.12c-.02-1.22-.96-2.58-2.11-3.01Zm-6.57 2.92c1.18 0 2.14.96 2.14 2.14 0 1.16-.91 2.09-2.06 2.13h-.1c-1.2-.04-2.1-.97-2.1-2.13-.01-1.18.95-2.14 2.12-2.14Zm2.26 9.33c-.61.4-1.4.61-2.19.61s-1.59-.2-2.19-.61c-.57-.38-.88-.9-.89-1.47 0-.56.32-1.1.89-1.48 1.21-.8 3.18-.8 4.39 0 .57.38.89.9.89 1.47-.01.56-.33 1.1-.9 1.48Z" fill="currentColor"/>'],
  TaskSquare: ['<path d="M12.37 8.88h5.25M6.38 8.88l.75.75 2.25-2.25M12.37 15.88h5.25M6.38 15.88l.75.75 2.25-2.25" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M9 22h6c5 0 7-2 7-7V9c0-5-2-7-7-7H9C4 2 2 4 2 9v6c0 5 2 7 7 7Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>',
    '<path d="M16.19 2H7.81C4.17 2 2 4.17 2 7.81v8.38C2 19.83 4.17 22 7.81 22h8.38c3.64 0 5.81-2.17 5.81-5.81V7.81C22 4.17 19.83 2 16.19 2ZM9.97 14.9l-2.25 2.25c-.15.15-.34.22-.53.22s-.39-.07-.53-.22l-.75-.75c-.3-.29-.3-.77 0-1.06.29-.29.76-.29 1.06 0l.22.22 1.72-1.72c.29-.29.76-.29 1.06 0 .29.29.29.77 0 1.06Zm0-7-2.25 2.25c-.15.15-.34.22-.53.22s-.39-.07-.53-.22l-.75-.75c-.3-.29-.3-.77 0-1.06.29-.29.76-.29 1.06 0l.22.22 1.72-1.72c.29-.29.76-.29 1.06 0 .29.29.29.77 0 1.06Zm7.59 8.72h-5.25c-.41 0-.75-.34-.75-.75s.34-.75.75-.75h5.25a.749.749 0 1 1 0 1.5Zm0-7h-5.25c-.41 0-.75-.34-.75-.75s.34-.75.75-.75h5.25a.749.749 0 1 1 0 1.5Z" fill="currentColor"/>'],
  TrendUp: ['<path d="m16.5 9.5-4.2 4.2-1.6-2.4-3.2 3.2" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M14.5 9.5h2v2" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M9 22h6c5 0 7-2 7-7V9c0-5-2-7-7-7H9C4 2 2 4 2 9v6c0 5 2 7 7 7Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>',
    '<path d="M16.19 2H7.81C4.17 2 2 4.17 2 7.81v8.37C2 19.83 4.17 22 7.81 22h8.37c3.64 0 5.81-2.17 5.81-5.81V7.81C22 4.17 19.83 2 16.19 2Zm.69 9.53c0 .39-.31.7-.7.7-.39 0-.7-.31-.7-.7v-.18l-2.72 2.72c-.15.15-.35.22-.56.2a.665.665 0 0 1-.51-.31l-1.02-1.52-2.38 2.38c-.14.14-.31.2-.49.2s-.36-.07-.49-.2a.706.706 0 0 1 0-.99l2.98-2.98c.15-.15.35-.22.56-.2.21.02.4.13.51.31l1.02 1.52 2.11-2.11h-.18c-.39 0-.7-.31-.7-.7 0-.39.31-.7.7-.7h1.86c.09 0 .18.02.27.05.17.07.31.21.38.38.04.09.05.18.05.27v1.86h.01Z" fill="currentColor"/>'],
  WalletAdd: ['<path d="M17.751 7.05c-.24-.04-.49-.05-.75-.05h-10c-.28 0-.55.02-.81.06.14-.28.34-.54.58-.78l3.25-3.26a3.525 3.525 0 0 1 4.96 0l1.75 1.77c.64.63.98 1.43 1.02 2.26Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M9 19c0 .75-.21 1.46-.58 2.06A3.97 3.97 0 0 1 5 23a3.97 3.97 0 0 1-3.42-1.94A3.92 3.92 0 0 1 1 19c0-2.21 1.79-4 4-4s4 1.79 4 4ZM6.492 18.98h-2.98M5 17.52v2.99" stroke="currentColor" stroke-width="1.5" stroke-miterlimit="10" stroke-linecap="round" stroke-linejoin="round"/><path d="M22 12v5c0 3-2 5-5 5H7.63c.31-.26.58-.58.79-.94.37-.6.58-1.31.58-2.06 0-2.21-1.79-4-4-4-1.2 0-2.27.53-3 1.36V12c0-2.72 1.64-4.62 4.19-4.94.26-.04.53-.06.81-.06h10c.26 0 .51.01.75.05C20.33 7.35 22 9.26 22 12Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M22 12.5h-3c-1.1 0-2 .9-2 2s.9 2 2 2h3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>',
    '<path d="M5 15c-2.21 0-4 1.79-4 4 0 .75.21 1.46.58 2.06A3.97 3.97 0 0 0 5 23c1.46 0 2.73-.78 3.42-1.94.37-.6.58-1.31.58-2.06 0-2.21-1.79-4-4-4Zm1.49 4.73h-.74v.78c0 .41-.34.75-.75.75s-.75-.34-.75-.75v-.78h-.74c-.41 0-.75-.34-.75-.75s.34-.75.75-.75h.74v-.71c0-.41.34-.75.75-.75s.75.34.75.75v.71h.74a.749.749 0 1 1 0 1.5ZM21.5 12.5H19c-1.1 0-2 .9-2 2s.9 2 2 2h2.5c.28 0 .5-.22.5-.5v-3c0-.28-.22-.5-.5-.5ZM16.53 5.4c.3.29.05.74-.37.74l-8.28-.01c-.48 0-.72-.58-.38-.92l1.75-1.76a3.796 3.796 0 0 1 5.35 0l1.89 1.91.04.04Z" fill="currentColor"/><path d="M21.87 18.66C21.26 20.72 19.5 22 17.1 22h-6.5c-.39 0-.64-.43-.48-.79.3-.7.49-1.49.49-2.21 0-3.03-2.47-5.5-5.5-5.5-.76 0-1.5.16-2.18.46-.37.16-.82-.09-.82-.49V12c0-2.72 1.64-4.62 4.19-4.94.25-.04.52-.06.8-.06h10c.26 0 .51.01.75.05 2.02.23 3.48 1.46 4.02 3.29.1.33-.14.66-.48.66H19.1c-2.17 0-3.89 1.98-3.42 4.23.33 1.64 1.85 2.77 3.52 2.77h2.19c.35 0 .58.34.48.66Z" fill="currentColor"/>'],
  WalletMinus: ['<path d="M14.262 15.998h-5" stroke="currentColor" stroke-width="1.5" stroke-miterlimit="10" stroke-linecap="round" stroke-linejoin="round"/><path d="m12.66 2.518-.03.07-2.9 6.73H6.88c-.68 0-1.33.14-1.92.39l1.75-4.18.04-.1.07-.16c.02-.06.04-.12.07-.17 1.31-3.03 2.79-3.72 5.77-2.58Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M18.05 9.518c-.45-.14-.93-.2-1.41-.2H9.73l2.9-6.73.03-.07c.15.05.29.12.44.18l2.21.93c1.23.51 2.09 1.04 2.61 1.68.1.12.18.23.25.36.09.14.16.28.2.43.04.09.07.18.09.26.27.84.11 1.87-.41 3.16Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M21.522 14.198v1.95c0 .2-.01.4-.02.6-.19 3.49-2.14 5.25-5.84 5.25h-7.8c-.24 0-.48-.02-.71-.05-3.18-.21-4.88-1.91-5.09-5.09-.03-.23-.05-.47-.05-.71v-1.95c0-2.01 1.22-3.74 2.96-4.49.6-.25 1.24-.39 1.92-.39h9.76c.49 0 .97.07 1.41.2 1.99.61 3.46 2.47 3.46 4.68Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/><path d="m6.71 5.528-1.75 4.18A4.894 4.894 0 0 0 2 14.198v-2.93c0-2.84 2.02-5.21 4.71-5.74ZM21.519 11.268v2.93c0-2.2-1.46-4.07-3.46-4.67.52-1.3.67-2.32.42-3.17-.02-.09-.05-.18-.09-.26 1.86.96 3.13 2.93 3.13 5.17Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>',
    '<path d="m11.94 2.212-2.41 5.61H7.12c-.4 0-.79.03-1.17.11l1-2.4.04-.09.06-.16c.03-.07.05-.13.08-.18 1.16-2.69 2.46-3.53 4.81-2.89ZM18.731 8.09l-.02-.01c-.6-.17-1.21-.26-1.83-.26h-6.26l2.25-5.23.03-.07c.14.05.29.12.44.17l2.21.93c1.23.51 2.09 1.04 2.62 1.68.09.12.17.23.25.36.09.14.16.28.2.43.04.09.07.17.09.26.15.51.16 1.09.02 1.74ZM18.288 9.52c-.45-.13-.92-.2-1.41-.2h-9.76c-.68 0-1.32.13-1.92.39a4.894 4.894 0 0 0-2.96 4.49v1.95c0 .24.02.47.05.71.22 3.18 1.92 4.88 5.1 5.09.23.03.46.05.71.05h7.8c3.7 0 5.65-1.76 5.84-5.26.01-.19.02-.39.02-.59V14.2a4.9 4.9 0 0 0-3.47-4.68Zm-3.79 7.23h-5c-.41 0-.75-.34-.75-.75s.34-.75.75-.75h5c.41 0 .75.34.75.75s-.34.75-.75.75Z" fill="currentColor"/>'],
};
const SIDE_ICONS = {
  dashboard: ISX.Home2,
  'reports/cashbook-receipts': ISX.MoneyRecive,
  'reports/cashbook-payments': ISX.MoneySend,
  journals: ISX.Book1,
  accounts: ISX.TaskSquare,
  'd-acreports': ISX.ChartSquare,
  members: ISX.Profile2User,
  deposits: ISX.WalletAdd,
  loans: ISX.MoneyTime,
  withdrawals: ISX.WalletMinus,
  savingsinterest: ISX.TrendUp,
  interest: ISX.PercentageSquare,
  payroll: ISX.ReceiptMinus,
  payrollpaste: ISX.DocumentUpload,
  staff: ISX.SecurityUser,
  admins: ISX.SecurityUser,
  momo: ISX.Mobile,
  messages: ISX.Message,
  products: ISX.Box,
  audit: ISX.Clock,
  branches: ISX.Building,
  yearcodes: ISX.Calendar2,
  mystatement: ISX.DocumentText
};
const svgOf = (d, cls) => `<svg class="${cls}" viewBox="0 0 24 24" fill="none" aria-hidden="true">${d}</svg>`;
const navIcon = k => SIDE_ICONS[k] ? `<span class="nav-ic">${svgOf(SIDE_ICONS[k][0], 'ic-l')}${svgOf(SIDE_ICONS[k][1], 'ic-b')}</span>` : '';

/** Opens or closes a drop-down in the menu. */
function openDrop(drop, open)
    {
        // the list grows and shrinks in style.css; while it's shut (or the menu is folded) its screens are out of the Tab order
        $('.nav-drop-btn', drop).setAttribute('aria-expanded', String(open));
        $('.nav-drop-list', drop).inert = !open || $('#app').classList.contains('side-collapsed');
    }

function navHtml(items) {
  return items.map(([k, t, kids]) => kids
    ? `<div class="nav-group"><div class="nav-heading">${esc(t)}</div>` +
      `<div class="nav-sub">${kids.map(([ck, ct, sub]) => sub
        // an entry with screens of its own is a drop-down: a click on it opens or closes the list
        ? `<div class="nav-drop"><button type="button" class="nav-drop-btn" aria-expanded="false">${navIcon(ck)}<span class="nav-label">${esc(ct)}</span>` +
          `<span class="chev" aria-hidden="true">${svgOf(ISX.ArrowDown2[0], '')}</span></button><div class="nav-drop-list" inert><div class="nav-drop-in">` +
          // the screens inside a drop-down hang from a line, so they carry no icon of their own
          sub.map(([sk, st]) => `<a href="#${sk}" data-k="${sk}"><span>${esc(st)}</span></a>`).join('') + `</div></div></div>`
        : `<a href="#${ck}" data-k="${ck}">${navIcon(ck)}<span class="nav-label">${esc(ct)}</span></a>`).join('')}</div></div>`
    : `<a href="#${k}" data-k="${k}">${navIcon(k)}<span class="nav-label">${esc(t)}</span></a>`).join('');
}

function start() {
  $('#login').hidden = true;
  $('#app').hidden = false;
  $('#nav').innerHTML = navHtml(navFor());
  // on the folded menu a drop-down first unfolds the menu, then opens
  $('#nav').querySelectorAll('.nav-drop-btn').forEach(b => b.onclick = () => {
    if ($('#app').classList.contains('side-collapsed')) { foldSide(false); openDrop(b.parentElement, true); }
    else openDrop(b.parentElement, b.getAttribute('aria-expanded') !== 'true');
  });
  $('#who').innerHTML = `${esc(S.me.fullName)}<small>${esc(label(S.me.role))}</small>`;
  $('#meBtn').dataset.tip = `${S.me.fullName} · ${label(S.me.role)}`;
  let folded = false;
  try { folded = localStorage.getItem('cpa.sideFolded') === '1'; } catch (e) { /* no storage: start unfolded */ }
  foldSide(folded, true);
  const names = String(S.me.fullName || S.me.username || '?').trim().split(/\s+/);
  $('#whoInitials').textContent = (names.length > 1 ? names[0][0] + names[names.length - 1][0] : names[0].slice(0, 2)).toUpperCase();
  const brand = isMgr() ? S.appName : S.me.branchName;
  $('#brandName').textContent = brand;
  // one branch: CPA Uganda's own logo; several: the coloured initials as before
  $('#brandMark').innerHTML = S.single
    ? `<span class="brand-logo" role="img" aria-label="${esc(brand)} logo"></span>`
    : markHtml(brand, isMgr() ? HQ : S.me.branchCode, true);
  document.title = isMgr() ? `${S.appName} · Support` : brand;
  const scopeText = isMgr() ? 'All branches' : S.single ? '' : `Sacco ID ${S.me.branchCode}`;
  $('#scope').textContent = scopeText;
  $('#scope').hidden = !scopeText;
  $('#chpw').hidden = isMember();
  route();
}

/* ------------------------------------------------------------------ routing */
const views = {};
const TITLES = { messages: 'Messages', yearcodes: 'New year codes', momo: 'Mobile money numbers', withdrawals: 'Savings withdrawal', mystatement: 'My statement', dashboard: 'Dashboard', branches: 'Branches', admins: 'Users', staff: 'Staff users', members: 'Members',
  statement: 'Member statement', loans: 'Loans', payroll: 'Payroll deductions', payrollpaste: 'Payroll from Excel', accounts: 'Chart of accounts', products: 'Product definitions', gl: 'General ledger', reports: 'Reports', deposits: 'Regular deposit', audit: 'Audit trail', journals: 'Journal entries', interest: 'Interest computation', savingsinterest: 'Savings interest' };

async function route() {
  if (!S.me) return;
  const home = isMember() ? 'mystatement' : 'dashboard';
  const [name, arg] = (location.hash.slice(1) || home).split('/');
  const pageKey = name === 'reports' ? 'reports/' + (arg || 'trial-balance') : name;
  const allowed = new Set(navFor().flatMap(([k, , kids]) => kids ? kids.flatMap(([ck, , sub]) => sub ? sub.map(s => s[0]) : [ck]) : [k]));
  if (!isMember()) allowed.add('statement');
  if (!allowed.has(pageKey)) {          // e.g. a manager-only page left in the address from an earlier sign-in
    if (location.hash !== '#' + home) location.hash = '#' + home; else views[home]($('#view'));
    return;
  }
  const view = views[name] || views[home];
  const key = views[name] ? name : home;
  const navKey = key === 'statement' ? 'members' : key === 'reports' ? 'reports/' + (arg || 'trial-balance') : key;
  document.querySelectorAll('#nav a').forEach(a => a.classList.toggle('active', a.dataset.k === navKey));
  // the drop-down holding the open screen is shown open, so the highlighted entry can be seen
  document.querySelectorAll('#nav .nav-drop').forEach(d => { if (d.querySelector('a.active')) openDrop(d, true); });
  $('#title').textContent = TITLES[key];
  const el = $('#view');
  el.innerHTML = '<div class="empty">Loading…</div>';
  try { await view(el, arg); }
  catch (err) { el.innerHTML = `<div class="error">${esc(err.message)}</div>`; }
}
window.addEventListener('hashchange', route);

/* ------------------------------------------------------------------ dashboard */
const stat = (cls, l, v) => `<div class="stat ${cls}"><div class="l">${esc(l)}</div><div class="v">${v}</div></div>`;

/** Big amounts shortened for the cards and chart axis: 4.22 bn, 15.3 m, 250 k (the full figure shows on hover). */
const compact = n => {
  const v = Number(n || 0), a = Math.abs(v);
  return a >= 1e9 ? (v / 1e9).toFixed(2) + ' bn' : a >= 1e6 ? (v / 1e6).toFixed(1) + ' m' : a >= 1e3 ? Math.round(v / 1e3) + ' k' : String(Math.round(v));
};

/** A figure card with an icon: the short amount large, the full amount on hover, a small line under it. */
const kpi = (icon, label, value, full, sub, href) =>
  `<${href ? `a href="${href}"` : 'div'} class="kpi"${full ? ` title="${esc(full)}"` : ''}>` +
  `<span class="kpi-ic"><svg viewBox="0 0 24 24" aria-hidden="true">${ICONS[icon] || ''}</svg></span>` +
  `<span class="kpi-txt"><span class="kpi-l">${esc(label)}</span><span class="kpi-v">${value}</span>${sub ? `<span class="kpi-s">${sub}</span>` : ''}</span>` +
  `</${href ? 'a' : 'div'}>`;

/** Money in (blue) and money out (black) for each month, as bars with a tooltip; the figures are also given as a table below it. */
function flowChart(months) {
  const W = 720, H = 250, L = 52, R = 8, T = 12, B = 30;
  const max = Math.max(1, ...months.flatMap(m => [Number(m.moneyIn), Number(m.moneyOut)]));
  // a round top for the axis: 1, 2 or 5 times a power of ten, four lines
  const raw = max / 4, p = Math.pow(10, Math.floor(Math.log10(raw))), step = [1, 2, 5, 10].map(x => x * p).find(x => x >= raw);
  const top = step * 4, y = v => T + (H - T - B) * (1 - v / top);
  const slot = (W - L - R) / months.length, bw = Math.min(18, (slot - 10) / 2);
  const bar = (x, v, cls) => {
    const h = Math.max(0, y(0) - y(v)); if (h < 0.5) return '';
    const r = Math.min(4, h, bw / 2), yt = y(v), yb = y(0);
    return `<path class="${cls}" d="M${x},${yb}V${yt + r}Q${x},${yt} ${x + r},${yt}H${x + bw - r}Q${x + bw},${yt} ${x + bw},${yt + r}V${yb}Z"/>`;
  };
  let svg = '';
  for (let i = 0; i <= 4; i++) {
    const v = step * i;
    svg += `<line class="grid" x1="${L}" x2="${W - R}" y1="${y(v)}" y2="${y(v)}"/><text class="ax" x="${L - 6}" y="${y(v) + 4}" text-anchor="end">${compact(v)}</text>`;
  }
  months.forEach((m, i) => {
    const x0 = L + slot * i + (slot - bw * 2 - 2) / 2;
    const [mon, yr] = m.label.split(' ');
    svg += bar(x0, Number(m.moneyIn), 'in') + bar(x0 + bw + 2, Number(m.moneyOut), 'out') +
      `<text class="ax" x="${L + slot * i + slot / 2}" y="${H - 10}" text-anchor="middle">${esc(mon)}${i === 0 || mon === 'Jan' ? ' ' + esc(yr.slice(2)) : ''}</text>` +
      `<rect class="hit" data-i="${i}" x="${L + slot * i}" y="${T}" width="${slot}" height="${H - T - B}"/>`;
  });
  return `<div class="chart-wrap"><svg class="chart" viewBox="0 0 ${W} ${H}" role="img" aria-label="Money in and out for the last twelve months">${svg}</svg>` +
    `<div class="chart-tip" hidden></div></div>`;
}

views.dashboard = async el => {
  const d = await api('/api/reports/dashboard');
  const hour = new Date().getHours();
  const hello = hour < 12 ? 'Good morning' : hour < 17 ? 'Good afternoon' : 'Good evening';
  const first = String(S.me.fullName || S.me.username).split(/[\s,]+/)[0];
  const ugx = n => 'UGX ' + compact(n);
  const thisMonth = d.months[d.months.length - 1];
  el.innerHTML =
    `<div class="dash-hello"><div><h3>${esc(hello)}, ${esc(first)}</h3>` +
    `<p class="muted">${esc(new Date().toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' }))}</p></div>` +
    (isMgr() ? '' : `<div class="quick"><a class="btn primary" href="#deposits">+ Regular deposit</a><a class="btn" href="#loans">Loan applications</a>` +
      `<a class="btn" href="#members">Member list</a><a class="btn" href="#reports/cashbook-receipts">Cashbook</a></div>`) + `</div>` +

    `<div class="kpis">` +
    kpi('members', 'Members', d.members.toLocaleString('en-US'), '', `${d.activeMembers.toLocaleString('en-US')} active`, '#members') +
    kpi('deposits', 'Savings', ugx(d.savings), 'UGX ' + money(d.savings), `${d.savers.toLocaleString('en-US')} savers · interest owed ${compact(d.savingsInterest)}`, '#reports/savings-report') +
    kpi('reports/shares-report', 'Shares', ugx(d.shares), 'UGX ' + money(d.shares), 'paid-up share capital', '#reports/shares-report') +
    kpi('loans', 'Loans outstanding', ugx(d.loans), 'UGX ' + money(d.loans), `${d.borrowers.toLocaleString('en-US')} borrowers`, '#reports/loan-report') +
    kpi('interest', 'Loan interest owed', ugx(d.loanInterest), 'UGX ' + money(d.loanInterest), 'charged, not yet paid', '#reports/loan-report') +
    kpi('payroll', 'Cash & bank', ugx(d.cashTotal), 'UGX ' + money(d.cashTotal), `${d.cash.length} accounts`, '#reports/cashbook-receipts') +
    `</div>` +

    `<div class="dash-grid">` +
    `<div class="panel"><div class="panel-head"><h3>Money in and out · last 12 months</h3>` +
    `<div class="legend"><span><i class="sw in"></i>Money in from members</span><span><i class="sw out"></i>Money out to members</span></div></div>` +
    flowChart(d.months) +
    `<p class="muted small-note">This month so far: in <strong>UGX ${money(thisMonth.moneyIn)}</strong>, out <strong>UGX ${money(thisMonth.moneyOut)}</strong>. ` +
    `In: savings, shares, membership and loan repayments. Out: withdrawals, refunds and loans paid out.</p>` +
    `<details class="figs"><summary>Show the figures</summary>` + table([
      { h: 'Month', f: r => esc(r.label) }, { h: 'Money in', num: 1, f: r => money(r.moneyIn) }, { h: 'Money out', num: 1, f: r => money(r.moneyOut) },
      { h: 'Difference', num: 1, f: r => money(Number(r.moneyIn) - Number(r.moneyOut)) }], d.months) + `</details></div>` +

    `<div class="panel"><h3>Cash and bank</h3>` +
    (d.cash.length ? `<div class="cash-list">${d.cash.map(c => `<div class="cash-row"><span><strong>${esc(c.code)}</strong> ${esc(c.name)}</span>` +
      `<span class="num">${money(c.balance)}</span></div>`).join('')}` +
      `<div class="cash-row total"><span>Total</span><span class="num">${money(d.cashTotal)}</span></div></div>` : '<div class="empty">Nothing held yet.</div>') +
    `</div></div>` +

    `<div class="dash-grid">` +
    `<div class="panel"><h3>Latest money in and out</h3>` + table([
      { h: 'Date', f: r => esc(r.date) },
      { h: 'Member', f: r => `${esc(r.memberNo)} – ${esc(r.memberName)}` },
      { h: 'What', f: r => `<span class="badge ${r.moneyIn ? 'in' : 'out'}">${r.moneyIn ? '↓ In' : '↑ Out'}</span> ${esc(r.type)}` },
      { h: 'Amount', num: 1, f: r => money(r.amount) }], d.recent) + `</div>` +
    `<div class="panel"><h3>Needs attention</h3><div class="todo">` +
    `<a class="todo-row" href="#loans"><span class="todo-n">${d.pendingLoans}</span><span>loan application${d.pendingLoans === 1 ? '' : 's'} waiting for a decision</span></a>` +
    `<a class="todo-row" href="#members"><span class="todo-n">${(d.members - d.activeMembers).toLocaleString('en-US')}</span><span>inactive member${d.members - d.activeMembers === 1 ? '' : 's'}</span></a>` +
    `<a class="todo-row" href="#reports/savings-report"><span class="todo-n">${compact(d.savingsInterest)}</span><span>savings interest owed to members (UGX)</span></a>` +
    `</div></div></div>` +
    (S.single ? '' : '<div id="branchSummary"></div>');

  // the chart's tooltip: the month under the pointer, with both figures
  const wrap = $('.chart-wrap', el), tip = $('.chart-tip', el);
  wrap.querySelectorAll('.hit').forEach(h => {
    h.addEventListener('mouseenter', () => {
      const m = d.months[Number(h.dataset.i)];
      tip.innerHTML = `<strong>${esc(m.label)}</strong><span><i class="sw in"></i>In ${money(m.moneyIn)}</span><span><i class="sw out"></i>Out ${money(m.moneyOut)}</span>`;
      tip.hidden = false;
      h.classList.add('on');
    });
    h.addEventListener('mousemove', e => {
      const box = wrap.getBoundingClientRect();
      tip.style.left = Math.min(box.width - tip.offsetWidth - 4, Math.max(4, e.clientX - box.left + 12)) + 'px';
      tip.style.top = Math.max(4, e.clientY - box.top - tip.offsetHeight - 10) + 'px';
    });
    h.addEventListener('mouseleave', () => { tip.hidden = true; h.classList.remove('on'); });
  });

  if (!S.single) await branchSummary($('#branchSummary'));
};

/** Several branches: every branch on one line with totals (not needed with one branch). */
async function branchSummary(el) {
  const rep = await api('/api/reports/summary');
  const t = rep.totals;
  const cols = [
    { h: 'Sacco ID', f: r => `<strong>${esc(r.branchCode)}</strong>` },
    { h: 'Branch', f: r => esc(r.branchName) },
    { h: 'Members', num: 1, f: r => r.members },
    { h: 'Savings', num: 1, f: r => money(r.savings) },
    { h: 'Shares', num: 1, f: r => money(r.shares) },
    { h: 'Loans issued', num: 1, f: r => money(r.loansDisbursed) },
    { h: 'Repaid', num: 1, f: r => money(r.loanRepayments) },
    { h: 'Loans outstanding', num: 1, f: r => money(r.loanOutstanding) },
    { h: 'Payroll collected', num: 1, f: r => money(r.payrollCollected) }
  ];
  const foot = rep.branches.length > 1
    ? `<tfoot><tr><td colspan="2">Group total</td><td class="num">${t.members}</td><td class="num">${money(t.savings)}</td><td class="num">${money(t.shares)}</td>` +
      `<td class="num">${money(t.loansDisbursed)}</td><td class="num">${money(t.loanRepayments)}</td><td class="num">${money(t.loanOutstanding)}</td><td class="num">${money(t.payrollCollected)}</td></tr></tfoot>`
    : '';
  el.innerHTML = `<div class="panel"><h3>${isMgr() ? 'Every branch' : 'Branch summary'}</h3>${table(cols, rep.branches, foot)}</div>`;
}

/* ------------------------------------------------------------------ branches (manager) */
views.branches = async el => {
  const list = await api('/api/branches');
  el.innerHTML = `<div class="panel"><div class="toolbar"><h3 class="spacer">Sacco branches</h3><button class="btn primary" data-act="addBranch">+ Add branch</button></div>` +
    table([
      { h: 'Sacco ID', f: r => `<strong>${esc(r.code)}</strong>` },
      { h: 'Name', f: r => esc(r.name) },
      { h: 'Location', f: r => esc(r.location || '') },
      { h: 'Status', f: r => `<span class="badge ${r.active ? 'ACTIVE' : 'REJECTED'}">${r.active ? 'Active' : 'Inactive'}</span>` },
      { h: '', f: r => `<button class="btn small" data-act="toggleBranch" data-id="${r.id}" data-value="${!r.active}">${r.active ? 'Deactivate' : 'Activate'}</button>` }
    ], list) + '</div>';
};

/* ------------------------------------------------------------------ mobile money numbers (manager) */
let MOMO = [];
views.momo = async el => {
  MOMO = await api('/api/mobile-money');
  el.innerHTML = `<div class="panel"><div class="toolbar"><h3 class="spacer">Mobile money numbers</h3><button class="btn primary" data-act="momoAdd">+ Add number</button></div>` +
    `<p class="muted" style="margin:0 0 10px">Each branch can have two numbers (for example MTN and Airtel). Every number gets its own account in the chart, ` +
    `so deposits can say which number received the money, and the branch sees each number's balance separately. Members see their branch's numbers on their statement screen.</p>` +
    table([
      { h: 'Branch', f: r => `<strong>${esc(r.branchCode)}</strong>` },
      { h: 'Network', f: r => esc(r.network) },
      { h: 'Number', f: r => `<strong>${esc(r.number)}</strong>` },
      { h: 'Registered name', f: r => esc(r.holderName || '') },
      { h: 'Account', f: r => `${esc(r.accountCode)} <span class="muted">${esc(r.accountName)}</span>` },
      { h: 'Status', f: r => `<span class="badge ${r.active ? 'ACTIVE' : 'REJECTED'}">${r.active ? 'In use' : 'Switched off'}</span>` },
      { h: '', f: r => `<div class="actions"><button class="btn small" data-act="momoEdit" data-id="${r.id}">Edit</button>` +
          `<button class="btn small" data-act="momoToggle" data-id="${r.id}">${r.active ? 'Switch off' : 'Switch on'}</button>` +
          `<button class="btn small danger" data-act="momoRemove" data-id="${r.id}">Remove</button></div>` }
    ], MOMO) + '</div>';
};

function momoForm(n, branches) {
  modal({
    title: n ? `Edit ${n.network} ${n.number}` : 'Add mobile money number', submit: n ? 'Save changes' : 'Add number',
    body: (n ? '' : sel('branchId', 'Branch', branches.map(b => [b.id, `${b.code} – ${b.name}`]), { req: 1 })) +
      `<div class="row2">${inp('network', 'Network (MTN, Airtel…)', { req: 1, value: n ? n.network : '' })}${inp('number', 'Mobile money number', { req: 1, value: n ? n.number : '', ph: '0772123456' })}</div>` +
      inp('holderName', 'Registered name', { value: n ? n.holderName || '' : '' }),
    onSubmit: async f => {
      const v = fd(f);
      const body = { branchId: n ? n.branchId : Number(v.branchId), network: v.network, number: v.number, holderName: v.holderName };
      await api(n ? `/api/mobile-money/${n.id}` : '/api/mobile-money', { method: n ? 'PUT' : 'POST', body });
      toast(n ? 'Number updated' : 'Number added'); route();
    }
  });
}

/* ------------------------------------------------------------------ generate new year codes (manager) */
views.yearcodes = async el => {
  const draw = async done => {
    const s = await api('/api/year-codes');
    el.innerHTML = `<div class="panel" style="max-width:520px"><h3>Generate New Year Codes</h3>` +
      `<label>Current generated to<input value="${s.currentYear ?? ''}" readonly></label>` +
      `<label>Year to be generated<input value="${s.nextYear ?? ''}" readonly></label>` +
      (s.problem ? `<div class="error">${esc(s.problem)}</div>` : '') +
      `<div class="toolbar" style="margin-top:14px"><label style="flex-direction:row;align-items:center;gap:8px"><input type="checkbox" disabled ${done ? 'checked' : ''}> New year generated</label>` +
      `<span class="spacer"></span><button class="btn primary" id="yrRun" ${s.problem ? 'disabled' : ''}>Run new year</button></div>` +
      `<p class="muted">This moves every date in the month data forward one year, adds those periods to the intervals as year ${esc(s.nextYear ?? '')}, ` +
      `and sets the current year to ${esc(s.nextYear ?? '')}.</p></div>`;
    const run = $('#yrRun');
    if (run) run.onclick = () => modal({
      title: `Generate ${s.nextYear}?`, submit: 'Run new year',
      body: `<p class="muted">The month data moves forward one year, its periods are added to the intervals as ${s.nextYear}, and the current year becomes ${s.nextYear}. ` +
        `The same year cannot be generated twice.</p>`,
      onSubmit: async () => {
        const r = await api('/api/year-codes', { method: 'POST' });
        toast(`Success: ${r.periods} periods generated for ${r.year}`);
        await draw(true);
      }
    });
  };
  await draw(false);
};

/* ------------------------------------------------------------------ branch admins (manager) */
views.admins = async el => {
  const list = await api('/api/users');
  el.innerHTML = `<div class="panel"><div class="toolbar"><h3 class="spacer">Users</h3><button class="btn primary" data-act="addAdmin">+ Add user</button></div>` +
    (isMgr() ? '' : `<p class="muted" style="margin:0 0 10px">Treasurers and cashiers can do everything a branch admin can in this branch, except manage users.</p>`) +
    table([
      { h: 'Username', f: r => `<strong>${esc(r.username)}</strong>` },
      { h: 'Full name', f: r => esc(r.fullName) },
      { h: 'Role', f: r => label(r.role) },
      ...(S.single ? [] : [{ h: 'Branch', f: r => esc(r.branchCode || 'All branches') }]),
      { h: 'Status', f: r => `<span class="badge ${r.enabled ? 'ACTIVE' : 'REJECTED'}">${r.enabled ? 'Enabled' : 'Disabled'}</span>` },
      { h: '', f: r => (isMgr() || ['TREASURER', 'CASHIER'].includes(r.role)) ? `<div class="actions">` +
          (r.id === S.me.id ? '' : `<button class="btn small" data-act="toggleUser" data-id="${r.id}" data-value="${!r.enabled}">${r.enabled ? 'Disable' : 'Enable'}</button>`) +
          `<button class="btn small" data-act="resetPassword" data-id="${r.id}" data-name="${esc(r.username)}">Reset password</button></div>` : '' }
    ], list) + '</div>';
};
views.staff = views.admins;

/* ------------------------------------------------------------------ members */
views.members = async el => {
  const branches = isMgr() ? await api('/api/branches') : [];
  el.innerHTML = `<div class="panel"><form class="toolbar" id="memberFilter">` +
    `<label class="grow">Search<input name="q" placeholder="Name or member no."></label>` +
    (isMgr() ? sel('branchId', 'Branch', branches.map(b => [b.id, `${b.code} – ${b.name}`]), { blank: 'All branches' }) : '') +
    `<button class="btn">Search</button><span class="spacer"></span>` +
    (isMgr() ? '' : '<button type="button" class="btn primary" data-act="addMember">+ Add member</button>') +
    `</form><div id="memberTable"></div></div>`;
  const load = async () => {
    const f = fd($('#memberFilter'));
    const qs = new URLSearchParams();
    if (f.q) qs.set('q', f.q);
    if (f.branchId) qs.set('branchId', f.branchId);
    const rows = await api('/api/members?' + qs);
    $('#memberTable').innerHTML = table([
      ...(isMgr() ? [{ h: 'Branch', f: r => esc(r.branchCode) }] : []),
      { h: 'Member no.', f: r => `<strong>${esc(r.memberNo)}</strong>` },
      { h: 'Name', f: r => esc(r.fullName) },
      { h: 'Phone', f: r => esc(r.phone || '') },
      { h: 'Employer', f: r => esc(r.employer || '') },
      { h: 'Payroll no.', f: r => esc(r.payrollNo || '') },
      { h: '', f: r => rowBtns('member', r.id, isMgr() ? '' : `<button class="btn small" data-act="resetLogin" data-id="${r.id}" data-name="${esc(r.fullName)}">Reset login</button>`) }
    ], rows);
  };
  $('#memberFilter').addEventListener('submit', e => { e.preventDefault(); load().catch(x => toast(x.message, true)); });
  await load();
};

views.statement = async (el, id) => {
  const [s, ml] = await Promise.all([api(`/api/members/${encodeURIComponent(id)}/statement`), api(`/api/member-ledger?memberId=${encodeURIComponent(id)}`)]);
  const m = s.member;
  el.innerHTML =
    `<p><a href="#members">← Back to members</a></p>` +
    `<div class="panel"><div class="member-head"><div class="photo-box" id="photoBox"></div><div class="grow">` +
    `<h3>${esc(m.fullName)} <span class="muted">· ${esc(m.memberNo)}${S.single ? '' : ' · ' + esc(m.branchCode)}</span></h3>` +
    `<div class="kv"><div><span>Phone</span>${esc(m.phone || '—')}</div><div><span>Employer</span>${esc(m.employer || '—')}</div><div><span>Payroll no.</span>${esc(m.payrollNo || '—')}</div></div></div></div></div>` +
    `<div class="cards">${stat('', 'Savings balance', money(s.savingsBalance))}${stat('b2', 'Shares balance', money(s.sharesBalance))}` +
    `${stat('b3', 'Loan outstanding', money(s.loans.reduce((a, l) => a + Number(l.outstanding), 0)))}</div>` +
    `<div class="panel"><h3>Loans</h3>${loanTable(s.loans, false)}</div>` +
    `<div class="panel"><h3>Member ledger</h3>` +
    (ml.balances.length ? `<div class="cards">${ml.balances.map(b => stat('b2', `${b.productCode} · ${b.productName}`, money(b.balance))).join('')}</div>` : '') +
    table([
      { h: 'Date', f: r => esc(r.date) },
      ...(S.single ? [] : [{ h: 'Branch', f: r => esc(r.branchCode) }]),
      { h: 'Product ID', f: r => `<strong>${esc(r.productCode)}</strong>` },
      { h: 'Category', f: r => esc(label(r.category)) },
      { h: 'Description', f: r => esc(r.description || '') },
      { h: 'Debit', num: 1, f: r => Number(r.debit) ? money(r.debit) : '' },
      { h: 'Credit', num: 1, f: r => Number(r.credit) ? money(r.credit) : '' },
      { h: 'Balance', num: 1, f: r => money(r.balance) }
    ], ml.lines, '', true) + `</div>`;

  // the member's photo; any staff login can add, change or remove it (the support login only looks)
  const photoPath = `/api/members/${encodeURIComponent(id)}/photo`;
  const drawPhoto = async () => {
    const url = await imageUrl(photoPath);
    const canEdit = !isMgr();
    $('#photoBox').innerHTML = (url ? `<img class="photo" src="${url}" alt="Photo of ${esc(m.fullName)}">` : '<div class="no-photo">No photo</div>') +
      (!canEdit ? '' : `<input type="file" accept="image/*" id="photoFile" hidden><input type="file" accept="image/*" capture="user" id="photoCam" hidden>` +
        `<div class="photo-btns"><button type="button" class="btn small" id="photoPick">${url ? 'Change photo' : 'Add photo'}</button>` +
        `<button type="button" class="btn small" id="photoTake">Take photo</button>` +
        (url ? `<button type="button" class="btn small danger" id="photoDel">Remove</button>` : '') + `</div>`);
    if (!canEdit) return;
    const upload = async file => {
      if (!file) return;
      try {
        await api(photoPath, { method: 'PUT', body: { image: await photoData(file) } });
        toast('Photo saved');
        await drawPhoto();
      } catch (x) { toast(x.message, true); }
    };
    $('#photoPick').onclick = () => $('#photoFile').click();
    $('#photoTake').onclick = () => $('#photoCam').click();
    $('#photoFile').onchange = e => upload(e.target.files[0]);
    $('#photoCam').onchange = e => upload(e.target.files[0]);
    if (url) $('#photoDel').onclick = async () => {
      if (!confirm(`Remove the photo of ${m.fullName}?`)) return;
      try { await api(photoPath, { method: 'DELETE' }); toast('Photo removed'); await drawPhoto(); } catch (x) { toast(x.message, true); }
    };
  };
  await drawPhoto();
};

/* ------------------------------------------------------------------ loans */
function loanTable(rows, withActions) {
  return table([
    { h: 'No.', f: r => '#' + r.id + (r.topUpOfLoanId ? ` <span class="muted">top-up of #${r.topUpOfLoanId}</span>` : '') },
    { h: 'Loan date', f: r => esc(r.appliedOn || '') },
    ...(isMgr() ? [{ h: 'Branch', f: r => esc(r.branchCode) }] : []),
    { h: 'Member', f: r => esc(r.memberName) },
    { h: 'Principal', num: 1, f: r => money(r.principal) },
    { h: 'Rate % p.a.', num: 1, f: r => `${r.annualRatePercent} <span class="muted">${r.interestMethod === 'REDUCING_BALANCE' ? 'reducing' : 'straight-line'}</span>` },
    { h: 'Months', num: 1, f: r => r.termMonths },
    { h: 'Monthly installment', num: 1, f: r => money(r.installment) },
    { h: 'Charges', num: 1, f: r => money(chargesOf(r)) },
    { h: 'Total due', num: 1, f: r => money(r.totalDue) },
    { h: 'Repaid', num: 1, f: r => money(r.repaid) },
    { h: 'Outstanding', num: 1, f: r => money(r.outstanding) },
    { h: 'Disbursed', f: r => esc(r.disbursedOn || '—') },
    { h: 'Status', f: r => `<span class="badge ${r.status}">${esc(label(r.status))}</span>` },
    ...(withActions ? [{ h: '', f: r => {
      const flow = isMgr() ? '' : r.status === 'PENDING'
        ? `<button class="btn small primary" data-act="disburse" data-id="${r.id}">Disburse</button><button class="btn small" data-act="reject" data-id="${r.id}">Reject</button>`
        : r.status === 'ACTIVE'
          ? `<button class="btn small primary" data-act="repay" data-id="${r.id}" data-out="${r.remaining}">Record repayment</button>` +
            `<button class="btn small" data-act="topUp" data-id="${r.id}">Top up</button>` : '';
      return rowBtns('loan', r.id, `<button class="btn small" data-act="schedule" data-id="${r.id}">Schedule</button>` + flow);
    } }] : [])
  ], rows);
}

views.loans = async el => {
  const branches = isMgr() ? await api('/api/branches') : [];
  el.innerHTML = `<div class="panel"><form class="toolbar" id="loanFilter">` +
    (isMgr() ? sel('branchId', 'Branch', branches.map(b => [b.id, `${b.code} – ${b.name}`]), { blank: 'All branches' }) : '') +
    sel('status', 'Status', ['PENDING', 'ACTIVE', 'CLEARED', 'REJECTED'].map(s => [s, label(s)]), { blank: 'All statuses' }) +
    `<button class="btn">Apply</button><span class="spacer"></span>` +
    (isMgr() ? '' : '<button type="button" class="btn primary" data-act="addLoan">+ New loan</button>') +
    `</form><div id="loanTable"></div></div>`;
  const load = async () => {
    const f = fd($('#loanFilter'));
    const qs = new URLSearchParams();
    ['branchId', 'status'].forEach(k => { if (f[k]) qs.set(k, f[k]); });
    $('#loanTable').innerHTML = loanTable(await api('/api/loans?' + qs), true);
  };
  $('#loanFilter').addEventListener('submit', e => { e.preventDefault(); load().catch(x => toast(x.message, true)); });
  await load();
};

/* ------------------------------------------------------------------ payroll */
views.payroll = async el => {
  const rows = await api('/api/payroll');
  el.innerHTML = `<div class="panel"><div class="toolbar"><h3 class="spacer">Posted deduction batches</h3>` +
    (isMgr() ? '' : '<button class="btn primary" data-act="addPayroll">+ Post payroll deductions</button>') + `</div>` +
    table([
      ...(isMgr() ? [{ h: 'Branch', f: r => esc(r.branchCode) }] : []),
      { h: 'Date', f: r => esc(r.paymentDate || '—') },
      { h: 'Period', f: r => `<strong>${esc(r.period)}</strong>` },
      { h: 'Employer', f: r => esc(r.employer) },
      { h: 'Receiving account', f: r => esc(r.receivingAccount || '—') },
      { h: 'Members', num: 1, f: r => r.lineCount },
      { h: 'Total deducted', num: 1, f: r => money(r.totalAmount) },
      { h: 'Posted by', f: r => esc(r.createdBy || '') },
      { h: '', f: r => rowBtns('batch', r.id) }
    ], rows) + '</div>';
};

/* ------------------------------------------------------------------ payroll from Excel (paste) */
views.payrollpaste = async el => {
  const [members, loans, accts] = await Promise.all([api('/api/members'), api('/api/loans?status=ACTIVE'), api('/api/accounts')]);
  const receiving = accts.filter(a => a.type === 'ASSET' && a.active);
  const byNo = new Map(members.map(m => [String(m.memberNo).trim().toLowerCase(), m]));
  const num = v => { const n = Number(String(v ?? '').replace(/[,\s]/g, '')); return Number.isFinite(n) ? n : NaN; };
  const employers = [...new Set(members.map(m => m.employer).filter(Boolean))];
  let parsed = [];

  el.innerHTML = `<div class="panel"><form id="ppForm">` +
    `<div class="row2"><label>Employer<input name="employer" list="ppEmployers" required><datalist id="ppEmployers">${employers.map(e => `<option value="${esc(e)}">`).join('')}</datalist></label>` +
    inp('period', 'Month', { type: 'month', req: 1, value: today().slice(0, 7) }) + `</div>` +
    `<div class="row2">${sel('receivingAccountId', 'Receiving account (where the money was received)', receiving.map(a => [a.id, `${a.code} – ${a.name}`]), { req: 1 })}` +
    `${inp('date', 'Transaction date', { type: 'date', req: 1, value: today() })}</div>` +
    `<label>Paste from Excel<textarea name="sheet" id="ppSheet" rows="9" placeholder="Copy the cells in Excel and paste here.&#10;Columns in this order: Member ID, Member name, Shares, Savings, Loan repayment"></textarea></label>` +
    `<p class="muted" style="margin:0">Columns, left to right: <strong>Member ID · Member name · Shares · Savings · Loan repayment</strong>. A heading row is ignored. ` +
    `Members are found by Member ID; a repayment goes to the member's active loan. Nothing is posted until every line is correct and you click <strong>Post batch</strong>.</p>` +
    `<div id="ppOut"></div></form></div>`;

  const show = () => {
    const text = $('#ppSheet').value;
    parsed = text.split(/\r?\n/).map(l => l.split('\t')).filter(c => c.some(x => x.trim() !== '')).map(c => ({
      no: (c[0] || '').trim(), name: (c[1] || '').trim(), shares: num(c[2] || 0), savings: num(c[3] || 0), repay: num(c[4] || 0)
    }));
    // a heading row (Member ID, Member name, Shares ...) is skipped: its id is unknown and its amounts are words
    if (parsed.length && !byNo.has(parsed[0].no.toLowerCase()) && [parsed[0].shares, parsed[0].savings, parsed[0].repay].some(Number.isNaN)) parsed.shift();
    parsed.forEach(r => {
      r.errors = []; r.warn = '';
      const m = byNo.get(r.no.toLowerCase());
      if (!m) r.errors.push('Member ID not found');
      [['shares', 'Shares'], ['savings', 'Savings'], ['repay', 'Loan repayment']].forEach(([k, n]) => {
        if (Number.isNaN(r[k])) { r.errors.push(n + ' is not a number'); r[k] = 0; } else if (r[k] < 0) r.errors.push(n + ' is negative');
      });
      if (!r.errors.length && r.shares + r.savings + r.repay === 0) r.errors.push('No amounts');
      if (m) {
        r.member = m;
        if (r.name && r.name.toLowerCase() !== m.fullName.toLowerCase()) r.warn = 'Name in the sheet differs from ' + m.fullName;
        if (r.repay > 0) {
          const mine = loans.filter(l => l.memberId === m.id).sort((a, b) => a.id - b.id);
          if (!mine.length) r.errors.push('No active loan to repay');
          else { r.loan = mine[0]; if (r.repay > Number(r.loan.remaining)) r.errors.push('More than the loan still owes (' + money(r.loan.remaining) + ')'); }
        }
      }
    });
    const bad = parsed.filter(r => r.errors.length).length;
    const sum = k => parsed.reduce((a, r) => a + (r[k] || 0), 0);
    $('#ppOut').innerHTML = !parsed.length ? '' :
      `<h3>${parsed.length} line${parsed.length === 1 ? '' : 's'} read${bad ? ` · <span style="color:#b00020">${bad} to fix</span>` : ' · all correct'}</h3>` +
      table([
        { h: 'Member ID', f: r => esc(r.no) },
        { h: 'Member name', f: r => esc(r.member ? r.member.fullName : r.name) + (r.warn ? `<div class="muted">${esc(r.warn)}</div>` : '') },
        { h: 'Shares', num: 1, f: r => money(r.shares) },
        { h: 'Savings', num: 1, f: r => money(r.savings) },
        { h: 'Loan repayment', num: 1, f: r => money(r.repay) + (r.loan ? `<div class="muted">loan #${r.loan.id}</div>` : '') },
        { h: 'Check', f: r => r.errors.length ? `<span class="badge PENDING">${esc(r.errors.join('; '))}</span>` : '<span class="badge ACTIVE">OK</span>' }
      ], parsed, `<tfoot><tr><td colspan="2">Total ${money(sum('shares') + sum('savings') + sum('repay'))}</td><td class="num">${money(sum('shares'))}</td><td class="num">${money(sum('savings'))}</td><td class="num">${money(sum('repay'))}</td><td></td></tr></tfoot>`) +
      (isMgr() ? '<p class="muted">The manager can look at payroll but only a branch admin can post it.</p>'
        : `<div class="toolbar" style="margin-top:14px"><button class="btn primary" id="ppPost" ${bad ? 'disabled' : ''}>Post batch</button></div>`);
    const post = $('#ppPost');
    if (post) post.onclick = async () => {
      const v = fd($('#ppForm'));
      if (!v.employer || !v.period || !v.receivingAccountId || !v.date) { toast('Fill in the employer, month, receiving account and date', true); return; }
      post.disabled = true;
      try {
        await api('/api/payroll', { method: 'POST', body: { employer: v.employer, period: v.period, receivingAccountId: Number(v.receivingAccountId), date: v.date,
          lines: parsed.map(r => ({ memberId: r.member.id, savings: r.savings, shares: r.shares, loanRepayment: r.repay, loanId: r.loan ? r.loan.id : null })) } });
        toast('Payroll batch posted'); location.hash = '#payroll';
      } catch (x) { toast(x.message, true); post.disabled = false; }
    };
  };
  const bank = receiving.find(a => a.code === '1200');
  if (bank) $('select[name=receivingAccountId]', el).value = bank.id;
  $('#ppSheet').addEventListener('input', show);
};

/* ------------------------------------------------------------------ my statement (a member's own login) */
views.mystatement = async el => {
  el.innerHTML = `<div class="panel"><form class="toolbar" id="myForm">` +
    inp('from', 'From', { type: 'date' }) + inp('to', 'To', { type: 'date', value: today() }) +
    `<button class="btn primary">View</button><button type="button" class="btn" id="myOpen">Open PDF</button>` +
    `<button type="button" class="btn" id="myDownload">Download PDF</button><span class="spacer"></span>` +
    `<button type="button" class="btn" id="myPassword">Change password</button></form></div><div id="myOut"></div>`;
  el.insertAdjacentHTML('afterbegin', '<div class="panel" id="myPayTo" hidden></div>');
  api('/api/my/pay-to').then(list => {
    if (!list.length) return;
    const box = $('#myPayTo');
    box.hidden = false;
    box.innerHTML = '<h3>Send money to</h3><div class="kv">' + list.map(n => `<div><span>${esc(n.network)}${n.holderName ? ' · ' + esc(n.holderName) : ''}</span><strong>${esc(n.number)}</strong></div>`).join('') + '</div>';
  }).catch(() => { /* the numbers are a convenience */ });
  const form = $('#myForm');
  const query = () => {
    const f = fd(form), qs = new URLSearchParams();
    ['from', 'to'].forEach(k => { if (f[k]) qs.set(k, f[k]); });
    return qs;
  };
  const run = async () => {
    $('#myOut').innerHTML = renderDoc(await api('/api/my/statement?' + query()));
    await addStatementPhoto($('#myOut'), '/api/my/photo');
  };
  form.addEventListener('submit', e => { e.preventDefault(); run().catch(x => toast(x.message, true)); });
  $('#myOpen').onclick = async () => {
    const w = window.open('', '_blank');
    if (!w) { toast('Allow pop-ups for this site to open the PDF', true); return; }
    w.document.write('<p style="font-family:sans-serif">Preparing PDF…</p>');
    try { w.location.href = await pdfUrl('/api/my/statement/pdf?' + query()); }
    catch (x) { w.close(); toast(x.message, true); }
  };
  $('#myDownload').onclick = async () => {
    try {
      const a = document.createElement('a');
      a.href = await pdfUrl('/api/my/statement/pdf?' + query());
      a.download = `my-statement-${today()}.pdf`;
      document.body.appendChild(a); a.click(); a.remove();
    } catch (x) { toast(x.message, true); }
  };
  $('#myPassword').onclick = () => modal({
    title: 'Change password', submit: 'Save password',
    body: inp('password', 'New password (min 8 characters)', { type: 'password', req: 1 }),
    onSubmit: async f => {
      const password = fd(f).password;
      await api('/api/my/password', { method: 'PUT', body: { password } });
      S.auth = 'Basic ' + btoa(unescape(encodeURIComponent(`${S.me.username}:${password}`)));
      try { sessionStorage.setItem('auth', S.auth); } catch (x) { /* ignore */ }
      toast('Password changed');
    }
  });
  await run();
};

/* ------------------------------------------------------------------ chart of accounts */
const ACCOUNT_TYPES = ['ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE'];
const TYPE_HINT = { ASSET: '1xxx', LIABILITY: '2xxx', EQUITY: '3xxx', INCOME: '4xxx', EXPENSE: '5xxx' };

views.accounts = async el => {
  el.innerHTML = `<div class="panel"><form class="toolbar" id="accFilter">` +
    sel('type', 'Type', ACCOUNT_TYPES.map(t => [t, label(t)]), { blank: 'All types' }) +
    `<button class="btn">Apply</button><span class="spacer"></span>` +
    (canChart() ? '<button type="button" class="btn primary" data-act="addAccount">+ Add account</button>' : '') +
    `</form><p class="muted" style="margin:0">${S.single ? 'The chart of accounts. ' : 'One chart of accounts shared by every branch. '}` +
    (canChart() ? (isMgr() && !S.single ? 'You maintain it: a change here applies to all branches.' : 'You maintain it.') : 'Only the manager can change it.') +
    ` Codes follow the usual pattern: 1xxx assets, 2xxx liabilities, 3xxx equity, 4xxx income, 5xxx expenses.</p></div>` +
    `<div id="accGroups"></div>`;
  const load = async () => {
    const f = fd($('#accFilter'));
    const qs = new URLSearchParams();
    if (f.type) qs.set('type', f.type);
    const rows = await api('/api/accounts?' + qs);
    $('#accGroups').innerHTML = ACCOUNT_TYPES.map(t => {
      const g = rows.filter(r => r.type === t);
      if (!g.length) return '';
      return `<div class="panel"><h3>${esc(label(t))} <span class="muted">· ${g.length}</span></h3>` + table([
        { h: 'Code', f: r => `<strong>${esc(r.code)}</strong>` },
        { h: 'Account name', f: r => esc(r.name) + (r.system ? ' <span class="badge">Standard</span>' : '') },
        { h: 'Under', f: r => esc(r.parentName || '') },
        { h: 'Description', f: r => esc(r.description || '') },
        { h: 'Status', f: r => `<span class="badge ${r.active ? 'ACTIVE' : 'REJECTED'}">${r.active ? 'Active' : 'Inactive'}</span>` },
        { h: '', f: r => rowBtns('account', r.id, !canChart() ? '' : `<button class="btn small" data-act="toggleAccount" data-id="${r.id}" data-value="${!r.active}">${r.active ? 'Deactivate' : 'Activate'}</button>`) }
      ], g) + '</div>';
    }).join('') || '<div class="panel"><div class="empty">No accounts found.</div></div>';
  };
  $('#accFilter').addEventListener('submit', e => { e.preventDefault(); load().catch(x => toast(x.message, true)); });
  await load();
};

function parentOptions(all, type, selfId) {
  return all.filter(a => a.type === type && a.id !== selfId && a.active).map(a => [a.id, `${a.code} – ${a.name}`]);
}

function nextCode(all, type) {
  const nums = all.filter(a => a.type === type).map(a => parseInt(a.code, 10)).filter(n => !isNaN(n));
  return nums.length ? String(Math.max(...nums) + 10) : '';
}

/* ------------------------------------------------------------------ product definitions */
const PRODUCT_CATEGORIES = ['SAVINGS', 'SAVINGS_INTEREST', 'SHARES', 'MEMBERSHIP', 'LOAN', 'INTEREST_RECEIVABLE'];
const PRODUCT_ACCOUNT_TYPE = { SAVINGS: 'LIABILITY', SAVINGS_INTEREST: 'LIABILITY', SHARES: 'EQUITY', MEMBERSHIP: 'INCOME', LOAN: 'ASSET', INTEREST_RECEIVABLE: 'ASSET' };

views.products = async el => {
  const branches = isMgr() ? await api('/api/branches') : [];
  el.innerHTML = `<div class="panel"><form class="toolbar" id="prodFilter">` +
    (isMgr() ? sel('branchId', 'Branch', branches.map(b => [b.id, `${b.code} – ${b.name}`]), { blank: 'All branches' }) : '') +
    sel('category', 'Category', PRODUCT_CATEGORIES.map(c => [c, label(c)]), { blank: 'All categories' }) +
    `<button class="btn">Apply</button><span class="spacer"></span>` +
    (isMgr() ? '' : '<button type="button" class="btn primary" data-act="addProduct">+ Add product</button>') +
    `</form><p class="muted" style="margin:0">A product is anything that is posted to the member ledger (savings, shares, loans) or to its interest. Its details come from the chart of accounts.</p></div>` +
    `<div class="panel" id="prodOut"></div>`;
  const load = async () => {
    const f = fd($('#prodFilter'));
    const rows = (await api('/api/products' + (f.branchId ? `?branchId=${f.branchId}` : ''))).filter(r => !f.category || r.category === f.category);
    $('#prodOut').innerHTML = table([
      ...(isMgr() ? [{ h: 'Branch', f: r => esc(r.branchCode) }] : []),
      { h: 'Product ID', f: r => `<strong>${esc(r.productCode)}</strong>` },
      { h: 'Product details', f: r => `${esc(r.productDetails)} <span class="muted">· ${esc(r.accountCode)}</span>` },
      { h: 'Category', f: r => esc(label(r.category)) },
      { h: 'Remarks', f: r => esc(r.remarks || '') },
      { h: 'Interest', f: r => esc(r.interestProduct || '') },
      { h: 'Status', f: r => `<span class="badge ${r.active ? 'ACTIVE' : 'REJECTED'}">${r.active ? 'Active' : 'Inactive'}</span>` },
      { h: '', f: r => rowBtns('product', r.id) }
    ], rows);
  };
  $('#prodFilter').addEventListener('submit', e => { e.preventDefault(); load().catch(x => toast(x.message, true)); });
  await load();
};

/** Add (p = null) or edit a product. Product details are chosen from the chart of accounts. */
async function productModal(p) {
  const [prods, accts] = await Promise.all([api('/api/products'), api('/api/accounts')]);
  const editing = !!p;
  const acctOpts = type => accts.filter(a => a.type === type && a.active).map(a => [a.id, `${a.code} – ${a.name}`]);
  const optionsHtml = arr => arr.map(([v, t]) => `<option value="${esc(v)}">${esc(t)}</option>`).join('');
  const interestProducts = prods.filter(x => x.category === 'INTEREST_RECEIVABLE' && x.active).map(x => [x.id, `${x.productCode} – ${x.productDetails}`]);
  modal({
    title: editing ? `Edit ${p.productCode}` : 'Add product', submit: editing ? 'Save product' : 'Add product',
    body: (editing
      ? `<p class="muted">Product ID <strong>${esc(p.productCode)}</strong> · ${esc(label(p.category))}. These cannot be changed.</p>`
      : inp('productCode', 'Product ID', { req: 1, ph: 'e.g. COMSV' }) +
        sel('category', 'Category', PRODUCT_CATEGORIES.map(c => [c, label(c)]), { req: 1 })) +
      sel('accountId', 'Product details (from chart of accounts)', [], { req: 1 }) +
      inp('remarks', 'Remarks') +
      `<div id="loanOnly" hidden>` +
      sel('interestProductId', 'Interest receivable product', interestProducts, { blank: 'None' }) +
      sel('interestIncomeAccountId', 'Interest income account', acctOpts('INCOME'), { blank: 'None' }) + `</div>` +
      (editing ? sel('active', 'Status', [['true', 'Active'], ['false', 'Inactive']]) : ''),
    onMount: form => {
      const catSel = $('select[name=category]', form), acct = $('select[name=accountId]', form), loanOnly = $('#loanOnly', form);
      const cat = () => editing ? p.category : catSel.value;
      const refresh = () => {
        const opts = acctOpts(PRODUCT_ACCOUNT_TYPE[cat()]);
        acct.innerHTML = opts.length ? optionsHtml(opts) : '<option value="">No matching account in the chart</option>';
        loanOnly.hidden = cat() !== 'LOAN';
      };
      if (catSel) catSel.addEventListener('change', refresh);
      refresh();
      if (editing) {
        acct.value = p.accountId || '';
        $('input[name=remarks]', form).value = p.remarks || '';
        $('select[name=interestProductId]', form).value = p.interestProductId || '';
        $('select[name=interestIncomeAccountId]', form).value = p.interestIncomeAccountId || '';
        $('select[name=active]', form).value = String(p.active);
      }
    },
    onSubmit: async f => {
      const v = fd(f);
      const body = { accountId: Number(v.accountId), remarks: v.remarks,
        interestProductId: v.interestProductId ? Number(v.interestProductId) : null,
        interestIncomeAccountId: v.interestIncomeAccountId ? Number(v.interestIncomeAccountId) : null };
      if (editing) {
        body.active = v.active === 'true';
        await api(`/api/products/${p.id}`, { method: 'PUT', body });
      } else {
        await api('/api/products', { method: 'POST', body: { ...body, productCode: v.productCode.trim(), category: v.category } });
      }
      toast(editing ? 'Product updated' : 'Product added'); route();
    }
  });
}

/* ------------------------------------------------------------------ general ledger */
views.gl = async el => {
  const branches = isMgr() ? await api('/api/branches') : [];
  const accts = await api('/api/accounts');
  el.innerHTML = `<div class="panel"><form class="toolbar" id="glFilter">` +
    (isMgr() ? sel('branchId', 'Branch', branches.map(b => [b.id, `${b.code} – ${b.name}`]), { blank: 'All branches' }) +
               sel('accountCode', 'GL account', [], { blank: 'All accounts' })
             : sel('accountId', 'Account', accts.map(a => [a.id, `${a.code} – ${a.name}`]), { blank: 'All accounts' })) +
    inp('from', 'From', { type: 'date' }) + inp('to', 'To', { type: 'date' }) +
    `<button class="btn">Apply</button></form><div id="glOut"></div></div>`;
  const load = async () => {
    const f = fd($('#glFilter'));
    const qs = new URLSearchParams();
    ['branchId', 'accountId', 'accountCode', 'from', 'to'].forEach(k => { if (f[k]) qs.set(k, f[k]); });
    // with a start date the ledger is read from that date down, oldest first, like the old account ledger; without one, the latest lines come first
    if (f.from) { qs.set('oldestFirst', 'true'); qs.set('limit', 2000); }
    const rows = await api('/api/general-ledger?' + qs);
    const dmy = d => String(d).split('-').reverse().join('/');          // 2026-01-31 shown as 31/01/2026
    $('#glOut').innerHTML = (f.from && rows.length >= 2000 ? `<p class="muted">The first 2000 lines from ${esc(dmy(f.from))} are shown. Choose an earlier end date to see the rest.</p>` : '') + table([
      { h: 'Date', f: r => esc(dmy(r.date)) },
      ...(S.single ? [] : [{ h: 'Branch', f: r => esc(r.branchCode) }]),
      { h: 'Journal', f: r => esc(r.journalNo) },
      { h: 'Account', f: r => `<strong>${esc(r.accountCode)}</strong> ${esc(r.accountName)}` },
      { h: 'Product ID', f: r => esc(r.productCode || '') },
      { h: 'Member', f: r => esc(r.memberNo || '') },
      { h: 'Description', f: r => esc(r.description || '') },
      { h: 'Debit', num: 1, f: r => Number(r.debit) ? money(r.debit) : '' },
      { h: 'Credit', num: 1, f: r => Number(r.credit) ? money(r.credit) : '' },
      // a transaction opens its ledger lines; a manual journal opens its lines
      { h: '', f: r => `<button class="btn small" data-act="view" data-kind="${r.journalId ? 'journal' : 'entry'}" data-id="${r.journalId || r.entryId}">View</button>` }
    ], rows, '', true);
  };
  if (isMgr()) {
    // one chart for all branches: pick an account to see it in every branch, or in the branch chosen above
    $('select[name=accountCode]', $('#glFilter')).innerHTML = '<option value="">All accounts</option>' +
      accts.map(a => `<option value="${esc(a.code)}">${esc(a.code)} – ${esc(a.name)}</option>`).join('');
  }
  $('#glFilter').addEventListener('submit', e => { e.preventDefault(); load().catch(x => toast(x.message, true)); });
  await load();
};

/* ------------------------------------------------------------------ reports (view on screen, open or download as PDF) */
const REPORTS = {
  'trial-balance': { title: 'Trial balance', range: false },
  'income-expenditure': { title: 'Income & expenditure', range: true },
  'balance-sheet': { title: 'Balance sheet', range: false },
  'member-statement': { title: 'Member statement', range: true },
  'member-summary': { title: 'Member summary', range: false },
  'savings-report': { title: 'Savings report', range: false },
  'shares-report': { title: 'Shares report', range: false },
  'loan-report': { title: 'Loan report', range: false },
  'cashbook-receipts': { title: 'Cashbook receipts', range: true },
  'cashbook-payments': { title: 'Cashbook payments', range: true }
};

function renderDoc(doc) {
  const grid = doc.title === 'Member Statement';     // a line round every cell on the member statement
  const cell = (v, c) => `<td class="${c.numeric ? 'num' : ''}">${esc(v)}</td>`;
  const sections = doc.sections.map(s => {
    const head = s.columns.map(c => `<th class="${c.numeric ? 'num' : ''}">${esc(c.name)}</th>`).join('');
    const body = s.rows.length
      ? s.rows.map(r => `<tr>${r.map((v, i) => cell(v, s.columns[i])).join('')}</tr>`).join('')
      : `<tr><td colspan="${s.columns.length}" class="muted">No entries</td></tr>`;
    const foot = s.totalRow ? `<tfoot><tr>${s.totalRow.map((v, i) => cell(v, s.columns[i])).join('')}</tr></tfoot>` : '';
    return `<div class="panel"><h3${grid ? ' class="center"' : ''}>${esc(s.heading)}</h3><div class="tbl-wrap"><table${grid ? ' class="grid"' : ''}><thead><tr>${head}</tr></thead><tbody>${body}</tbody>${foot}</table></div></div>`;
  }).join('');
  // the member statement's headings are centred
  return `<div class="panel report-head${grid ? ' centered' : ''}"><div class="muted">${esc(S.appName)}</div>` +
    `<h3 class="rep-title">${esc(doc.title)}</h3><div class="rep-branch">${esc(doc.branchLine)}</div>` +
    (doc.subjectLine ? `<div>${esc(doc.subjectLine)}</div>` : '') + `<div class="muted">${esc(doc.periodLine)}</div>` +
    `<div class="rep-totals">${doc.totals.map(t => `<div class="rep-total"><span>${esc(t.label)}</span><strong>${esc(t.value)}</strong></div>`).join('')}</div>` +
    (doc.note ? `<p class="muted"><em>${esc(doc.note)}</em></p>` : '') + `</div>` + sections;
}

async function reportPdf(kind, qs) { return pdfUrl(`/api/reports/${kind}/pdf?${qs}`); }

/** A picture the server gives only to a signed-in user (a member's photo), as an address the page can show; null when there is none. */
async function imageUrl(path) {
  const res = await fetch(path, { headers: { Authorization: S.auth } });
  return res.ok ? URL.createObjectURL(await res.blob()) : null;
}

/** A chosen or newly taken photo as a JPEG of at most 600 pixels a side, the right way up (the server makes it smaller still). */
function photoData(file) {
  return new Promise((resolve, reject) => {
    const img = new Image(), src = URL.createObjectURL(file);
    img.onload = () => {
      const scale = Math.min(1, 600 / Math.max(img.naturalWidth, img.naturalHeight));
      const c = document.createElement('canvas');
      c.width = Math.max(1, Math.round(img.naturalWidth * scale));
      c.height = Math.max(1, Math.round(img.naturalHeight * scale));
      const g = c.getContext('2d');
      g.fillStyle = '#ffffff';
      g.fillRect(0, 0, c.width, c.height);
      g.drawImage(img, 0, 0, c.width, c.height);
      URL.revokeObjectURL(src);
      resolve(c.toDataURL('image/jpeg', 0.9));
    };
    img.onerror = () => { URL.revokeObjectURL(src); reject(new Error('That file is not a picture this browser can open')); };
    img.src = src;
  });
}

/** Puts a member's photo at the top right of a statement already on the page (nothing when there is no photo). */
async function addStatementPhoto(out, path) {
  const url = await imageUrl(path);
  const head = $('.report-head', out);
  if (url && head) head.insertAdjacentHTML('afterbegin', `<img class="rep-photo" src="${url}" alt="Member photo">`);
}

async function pdfUrl(path) {
  const res = await fetch(path, { headers: { Authorization: S.auth } });
  if (!res.ok) {
    let msg = 'Could not create the PDF';
    try { msg = (await res.json()).error || msg; } catch (e) { /* not json */ }
    throw new Error(msg);
  }
  return URL.createObjectURL(await res.blob());
}

views.reports = async (el, kind) => {
  if (!REPORTS[kind]) kind = 'trial-balance';
  const R = REPORTS[kind];
  const isStatement = kind === 'member-statement', isPayments = kind === 'cashbook-payments';
  const isCashbook = isPayments || kind === 'cashbook-receipts';
  $('#title').textContent = isCashbook ? (isPayments ? 'Cashbook · Payments' : 'Cashbook · Receipts') : 'Reports · ' + R.title;
  const branches = isMgr() ? await api('/api/branches') : [];
  const bookAccounts = isCashbook ? await api('/api/reports/cashbook-accounts') : [];
  // income and expenditure starts at the beginning of the year, the cashbook at the beginning of the month
  const fromDefault = kind === 'income-expenditure' ? `${new Date().getFullYear()}-01-01` : isCashbook ? today().slice(0, 8) + '01' : '';
  el.innerHTML = `<div class="panel"><form class="toolbar" id="repForm">` +
    (isMgr() ? sel('branchId', 'Branch', branches.map(b => [b.id, `${b.code} – ${b.name}`]),
      isStatement ? { req: 1, blank: 'Select a branch…' } : { blank: 'All branches (consolidated)' }) : '') +
    (isStatement ? sel('memberId', 'Member', [], { req: 1, blank: 'Select a member…' }) : '') +
    (isCashbook ? sel('accountId', 'Account', bookAccounts.map(a => [a.id, `${a.code} – ${a.name}`]), { blank: 'All cash, bank and mobile money' }) : '') +
    (R.range ? inp('from', 'From', { type: 'date', value: fromDefault }) : '') +
    inp('to', R.range ? 'To' : 'As at', { type: 'date', value: today() }) +
    `<button class="btn primary">View</button><button type="button" class="btn" id="repOpen">Open PDF</button>` +
    `<button type="button" class="btn" id="repDownload">Download PDF</button>` +
    (isCashbook && !isMgr() ? `<button type="button" class="btn primary" id="cashAdd">+ New ${isPayments ? 'payment' : 'receipt'}</button>` : '') +
    `</form></div><div id="repOut"></div>`;

  const form = $('#repForm');
  const query = () => {
    const f = fd(form), qs = new URLSearchParams();
    ['branchId', 'memberId', 'accountId', 'from', 'to'].forEach(k => { if (f[k]) qs.set(k, f[k]); });
    return qs;
  };
  const run = async () => {
    $('#repOut').innerHTML = renderDoc(await api(`/api/reports/${kind}?` + query()));
    const memberId = fd(form).memberId;
    if (isStatement && memberId) await addStatementPhoto($('#repOut'), `/api/members/${encodeURIComponent(memberId)}/photo`);
  };

  if (isStatement) {
    const memberSel = $('select[name=memberId]', form);
    const loadMembers = async () => {
      const f = fd(form);
      if (isMgr() && !f.branchId) { memberSel.innerHTML = '<option value="">Select a member…</option>'; return; }
      const rows = await api('/api/members' + (isMgr() ? `?branchId=${f.branchId}` : ''));
      memberSel.innerHTML = '<option value="">Select a member…</option>' +
        rows.map(m => `<option value="${m.id}">${esc(m.memberNo)} – ${esc(m.fullName)}</option>`).join('');
    };
    if (isMgr()) $('select[name=branchId]', form).addEventListener('change', () => loadMembers().catch(x => toast(x.message, true)));
    await loadMembers();
  }

  form.addEventListener('submit', e => { e.preventDefault(); run().catch(x => toast(x.message, true)); });

  // money in or out that is not a member's: saved as a two-line journal. A receipt debits the account the money came into and
  // credits what it is for; a payment debits what it is for (an expense...) and credits the account it was paid from.
  if ($('#cashAdd')) $('#cashAdd').onclick = async () => {
    try {
      const [accts, blocked] = await Promise.all([api('/api/accounts'), api('/api/journals/blocked-accounts')]);
      const bookIds = new Set(bookAccounts.map(a => a.id)), memberAccounts = new Set(blocked);
      const money = bookAccounts.filter(a => a.active).map(a => [a.id, `${a.code} – ${a.name}`]);
      const forOpts = accts.filter(a => a.active && !bookIds.has(a.id) && !memberAccounts.has(a.id))
        .map(a => [a.id, `${a.code} – ${a.name} (${label(a.type)})`]);
      const what = isPayments ? 'payment' : 'receipt';
      modal({
        title: `New ${what}`, submit: `Save ${what}`,
        body: `<div class="row2">${inp('date', 'Date *', { type: 'date', req: 1, value: today() })}` +
          `${inp('reference', isPayments ? 'Voucher no.' : 'Receipt no.', { ph: isPayments ? 'e.g. PV-001' : 'e.g. RCT-001' })}</div>` +
          inp('party', isPayments ? 'Paid to *' : 'Received from *', { req: 1, ph: isPayments ? 'e.g. UMEME' : 'e.g. Stanbic Bank' }) +
          ugx('amount', 'Amount *', { req: 1 }) +
          `<div class="row2">${sel('moneyId', isPayments ? 'Paid from *' : 'Received into *', money, { req: 1, blank: '— Select —' })}` +
          `${sel('forId', isPayments ? 'For (account debited) *' : 'For (account credited) *', forOpts, { req: 1, blank: '— Select —' })}</div>` +
          inp('notes', 'Notes') +
          (isPayments
            ? `<p class="muted" style="margin:0">For the sacco's own spending (rent, salaries, stationery, bank charges...). ` +
              `Savings withdrawals and loan payouts go through their own screens.</p>`
            : `<p class="muted" style="margin:0">For money that is not a member's payment (bank interest, sale of forms, T-shirts...). ` +
              `Member savings, shares and loan repayments go through Deposits → Regular deposit.</p>`),
        onSubmit: async f => {
          const v = fd(f), amount = Number(v.amount);
          if (!(amount > 0)) throw new Error('Enter an amount above zero');
          const moneyLine = { accountId: Number(v.moneyId), description: v.party };
          const forLine = { accountId: Number(v.forId), description: v.party };
          if (isPayments) { forLine.debit = amount; moneyLine.credit = amount; } else { moneyLine.debit = amount; forLine.credit = amount; }
          const j = await api('/api/journals', { method: 'POST', body: {
            date: v.date, reference: v.reference, narration: v.notes || (isPayments ? `Payment to ${v.party}` : `Receipt from ${v.party}`),
            lines: isPayments ? [forLine, moneyLine] : [moneyLine, forLine] } });
          toast(`${isPayments ? 'Payment' : 'Receipt'} saved as ${j.journalNo}`);
          run().catch(x => toast(x.message, true));
        }
      });
    } catch (x) { toast(x.message, true); }
  };

  $('#repOpen').onclick = async () => {
    if (!form.reportValidity()) return;
    const w = window.open('', '_blank');      // opened straight away so the browser does not block it
    if (!w) { toast('Allow pop-ups for this site to open the PDF', true); return; }
    w.document.write('<p style="font-family:sans-serif">Preparing PDF…</p>');
    try { w.location.href = await reportPdf(kind, query()); }
    catch (x) { w.close(); toast(x.message, true); }
  };
  $('#repDownload').onclick = async () => {
    if (!form.reportValidity()) return;
    try {
      const a = document.createElement('a');
      a.href = await reportPdf(kind, query());
      a.download = `${kind}-${today()}.pdf`;
      document.body.appendChild(a); a.click(); a.remove();
    } catch (x) { toast(x.message, true); }
  };

  if (!isStatement) await run();
};

/* ------------------------------------------------------------------ regular deposit */
const ugx = (name, lbl, o = {}) =>
  `<label>${esc(lbl)}<span class="input-group"><span>UGX</span><input name="${name}" type="number" step="0.01" min="0" ` +
  `${o.req ? 'required' : ''} placeholder="0" ${o.max !== undefined ? `max="${o.max}"` : ''}></span></label>`;
const DEPOSIT_TYPE = { SAVINGS_DEPOSIT: 'Savings', SHARE_PURCHASE: 'Shares', LOAN_REPAYMENT: 'Loan repayment' };

views.deposits = async el => {
  const branches = isMgr() ? await api('/api/branches') : [];
  el.innerHTML = `<div class="panel"><form class="toolbar" id="depFilter">` +
    (isMgr() ? sel('branchId', 'Branch', branches.map(b => [b.id, `${b.code} – ${b.name}`]), { blank: 'All branches' }) : '') +
    inp('from', 'From', { type: 'date' }) + inp('to', 'To', { type: 'date' }) +
    `<button class="btn">Apply</button><span class="spacer"></span>` +
    (isMgr() ? '' : '<button type="button" class="btn primary" id="depAdd">+ Add deposit</button>') +
    `</form><div id="depOut"></div></div>`;

  const loadHistory = async () => {
    const f = fd($('#depFilter')), qs = new URLSearchParams();
    ['branchId', 'from', 'to'].forEach(k => { if (f[k]) qs.set(k, f[k]); });
    const rows = await api('/api/deposits?' + qs);
    const total = rows.reduce((a, r) => a + Number(r.amount), 0);
    const cols = [
      { h: 'Date', f: r => esc(r.date) },
      ...(isMgr() ? [{ h: 'Branch', f: r => esc(r.branchCode) }] : []),
      { h: 'Receipt / Reference', f: r => esc(r.reference || '') },
      { h: 'Member', f: r => `${esc(r.memberNo)} · ${esc(r.memberName)}` },
      { h: 'Paid into', f: r => esc(DEPOSIT_TYPE[r.type] || label(r.type)) + (r.productCode ? ` <span class="muted">· ${esc(r.productCode)}</span>` : '') },
      { h: 'Receiving account', f: r => esc(r.receivingAccount || label(r.source)) },
      { h: 'Notes', f: r => esc(r.notes || '') },
      { h: 'Amount', num: 1, f: r => money(r.amount) },
      { h: '', f: r => rowBtns('entry', r.id) }
    ];
    $('#depOut').innerHTML = table(cols, rows, rows.length
      ? `<tfoot><tr><td colspan="${cols.length - 2}">Total (${rows.length} entries)</td><td class="num">${money(total)}</td><td></td></tr></tfoot>` : '');
  };
  $('#depFilter').addEventListener('submit', e => { e.preventDefault(); loadHistory().catch(x => toast(x.message, true)); });
  await loadHistory();
  if (isMgr()) return;
  $('#depAdd').addEventListener('click', () => openDepositForm(loadHistory).catch(x => toast(x.message, true)));
};

/** The Add deposit form, in a pop-up: one payment from one member, split across savings, shares and loan repayment. */
async function openDepositForm(onSaved) {
  // fresh data every time, so loan balances are current
  const [members, accts, prods, activeLoans] = await Promise.all([api('/api/members'), api('/api/accounts'), api('/api/products'), api('/api/loans?status=ACTIVE')]);
  const receiving = accts.filter(a => a.type === 'ASSET' && a.active);
  const prodOpts = cat => prods.filter(p => p.category === cat && p.active).map(p => [p.id, `${p.productCode} – ${p.productDetails}`]);
  const savingsProds = prodOpts('SAVINGS'), sharesProds = prodOpts('SHARES');
  const cents = x => Math.round(x * 100);
  const touched = { savings: false, shares: false, loanRepayment: false };
  let formEl = null;
  const field = n => $(`[name=${n}]`, formEl);
  const num = n => Number(field(n).value || 0);
  const opt = n => (field(n) && field(n).value) ? Number(field(n).value) : null;

  const updateSplit = () => {
    const split = num('savings') + num('shares') + num('loanRepayment');
    $('#splitTotal').textContent = 'UGX ' + money(split);
    const diff = cents(num('totalAmount')) - cents(split);
    const w = $('#splitWarn');
    if (num('totalAmount') > 0 && diff !== 0) { w.className = 'warn-text'; w.textContent = diff > 0 ? `UGX ${money(diff / 100)} still to allocate` : `Over the total by UGX ${money(-diff / 100)}`; }
    else if (num('totalAmount') > 0) { w.className = 'ok-text'; w.textContent = '✓ matches the total'; }
    else w.textContent = '';
  };

  modal({
    title: 'Add deposit', submit: 'Save Deposit', wide: true,
    body: `<p class="muted" style="margin-top:0">One payment, split across savings, shares, and loan repayment</p>` +
      `<label>Mobile money message <span class="muted">(optional: paste the SMS or WhatsApp text and click Read)</span>` +
      `<textarea name="momoText" rows="3" placeholder="You have received UGX 50,000 from JOHN MUGISHA 0772123456. Transaction ID: 12345678901 …"></textarea></label>` +
      `<div class="toolbar"><button type="button" class="btn small" id="momoRead">Read message</button><span id="momoInfo" class="hint"></span></div>` +
      `<div class="grid3">${sel('memberId', 'Member *', memberOptions(members), { req: 1, blank: '— Select member —' })}` +
      `${inp('date', 'Date *', { type: 'date', req: 1, value: today() })}${inp('reference', 'Receipt / Reference', { ph: 'e.g. RCT-001' })}</div>` +
      `<hr class="sep"><h4 class="sect">Total deposit received</h4>` +
      `<div style="max-width:340px">${ugx('totalAmount', 'Total Amount *', { req: 1 })}</div>` +
      `<p class="hint">The full amount the member is depositing right now — split it below.</p>` +
      `<h4 class="sect">Deposit split</h4>` +
      `<div class="grid3"><div>${ugx('savings', 'Savings')}` +
      (savingsProds.length > 1 ? sel('savingsProductId', 'Savings product', savingsProds) : '') + `</div>` +
      `<div>${ugx('shares', 'Shares')}` + (sharesProds.length > 1 ? sel('sharesProductId', 'Shares product', sharesProds) : '') + `</div>` +
      `<div>${ugx('loanRepayment', 'Loan Repayment')}<label id="loanPick" hidden>Loan<select name="loanId"></select></label>` +
      `<div class="hint" id="loanHint"></div></div></div>` +
      `<div id="splitInfo">Split total: <strong id="splitTotal">UGX 0.00</strong><span id="splitWarn"></span></div>` +
      `<div class="grid2">${sel('receivingAccountId', 'Receiving Account *', receiving.map(a => [a.id, `${a.code} – ${a.name}`]), { req: 1, blank: '— Select GL account —' })}` +
      `${inp('notes', 'Notes', { ph: 'Optional' })}</div>`,
    onMount: form => {
      formEl = form;
      const cash = receiving.find(a => a.code === '1100') || receiving[0];
      if (cash) field('receivingAccountId').value = cash.id;
      $('#momoRead', form).onclick = async () => {
        const info = $('#momoInfo', form);
        info.className = 'hint';
        try {
          const r = await api('/api/mobile-payments/parse', { method: 'POST', body: { text: field('momoText').value } });
          field('totalAmount').value = r.amount;
          if (!touched.savings && !touched.shares && !touched.loanRepayment) field('savings').value = r.amount;
          if (r.date) field('date').value = r.date;
          if (r.reference) field('reference').value = r.reference;
          if (r.receivingAccountId) field('receivingAccountId').value = r.receivingAccountId;
          if (r.memberId) { field('memberId').value = r.memberId; field('memberId').dispatchEvent(new Event('change')); }
          updateSplit();
          const who = r.memberId ? `member ${r.memberNo} · ${r.memberName}` : `no member has the number ${r.senderPhone || '(not found in the message)'} — choose the member`;
          info.className = r.duplicate ? 'warn-text' : 'ok-text';
          info.textContent = `UGX ${money(r.amount)} from ${r.senderName || r.senderPhone || 'unknown'} · ${who}` +
            (r.duplicate ? ' · THIS TRANSACTION WAS ALREADY RECORDED' : '') + ' · check, then save.';
        } catch (x) { info.className = 'warn-text'; info.textContent = x.message; }
      };

      ['savings', 'shares', 'loanRepayment'].forEach(n => field(n).addEventListener('input', () => { touched[n] = true; updateSplit(); }));
      field('totalAmount').addEventListener('input', () => {
        // until the split is edited by hand, the whole amount goes to savings
        if (!touched.savings && !touched.shares && !touched.loanRepayment) field('savings').value = field('totalAmount').value;
        updateSplit();
      });
      field('memberId').addEventListener('change', () => {
        const mine = activeLoans.filter(l => String(l.memberId) === field('memberId').value);
        const loanSel = field('loanId'), repay = field('loanRepayment');
        loanSel.innerHTML = mine.map(l => `<option value="${l.id}">Loan #${l.id} · to clear ${money(l.remaining)}</option>`).join('');
        $('#loanPick').hidden = mine.length < 2;
        repay.disabled = mine.length === 0;
        if (!mine.length) { repay.value = ''; touched.loanRepayment = false; }
        $('#loanHint').textContent = mine.length === 1 ? `Still to pay: UGX ${money(mine[0].remaining)}` : mine.length ? '' : 'No active loan';
        updateSplit();
      });
      field('memberId').dispatchEvent(new Event('change'));
    },
    onSubmit: async () => {
      if (cents(num('savings') + num('shares') + num('loanRepayment')) !== cents(num('totalAmount'))) {
        throw new Error('The split must add up to the total amount.');
      }
      const r = await api('/api/deposits', { method: 'POST', body: {
        memberId: Number(field('memberId').value), date: field('date').value, reference: field('reference').value,
        totalAmount: num('totalAmount'), savings: num('savings'), shares: num('shares'), loanRepayment: num('loanRepayment'),
        savingsProductId: opt('savingsProductId'), sharesProductId: opt('sharesProductId'),
        loanId: num('loanRepayment') > 0 ? opt('loanId') : null,
        receivingAccountId: Number(field('receivingAccountId').value), notes: field('notes').value } });
      toast(`Deposit saved · UGX ${money(r.total)}`);
      await onSaved();
    }
  });
}

/* ------------------------------------------------------------------ savings withdrawal */
views.withdrawals = async el => {
  const branches = isMgr() ? await api('/api/branches') : [];
  el.innerHTML = `<div class="panel"><form class="toolbar" id="wdFilter">` +
    (isMgr() ? sel('branchId', 'Branch', branches.map(b => [b.id, `${b.code} – ${b.name}`]), { blank: 'All branches' }) : '') +
    inp('from', 'From', { type: 'date' }) + inp('to', 'To', { type: 'date' }) +
    `<button class="btn">Apply</button><span class="spacer"></span>` +
    (isMgr() ? '' : '<button type="button" class="btn primary" id="wdAdd">+ Withdraw savings</button>') +
    `</form><div id="wdOut"></div></div>`;
  const load = async () => {
    const f = fd($('#wdFilter')), qs = new URLSearchParams();
    ['branchId', 'from', 'to'].forEach(k => { if (f[k]) qs.set(k, f[k]); });
    const rows = await api('/api/withdrawals?' + qs);
    const total = rows.reduce((a, r) => a + Number(r.amount), 0);
    const cols = [
      { h: 'Date', f: r => esc(r.date) },
      ...(isMgr() ? [{ h: 'Branch', f: r => esc(r.branchCode) }] : []),
      { h: 'Reference', f: r => esc(r.reference || '') },
      { h: 'Member', f: r => `${esc(r.memberNo)} · ${esc(r.memberName)}` },
      { h: 'Savings product', f: r => esc(r.productCode || '') },
      { h: 'Paid from', f: r => esc(r.receivingAccount || label(r.source)) },
      { h: 'Notes', f: r => esc(r.notes || '') },
      { h: 'Withdrawn', num: 1, f: r => money(r.amount) },
      { h: 'Fee', num: 1, f: r => Number(r.fee) ? money(r.fee) : '' },
      { h: 'Paid out', num: 1, f: r => `<strong>${money(Number(r.amount) - Number(r.fee || 0))}</strong>` },
      { h: '', f: r => rowBtns('entry', r.id) }
    ];
    const fees = rows.reduce((a, r) => a + Number(r.fee || 0), 0);
    $('#wdOut').innerHTML = table(cols, rows, rows.length
      ? `<tfoot><tr><td colspan="${cols.length - 4}">Total (${rows.length} withdrawals)</td><td class="num">${money(total)}</td><td class="num">${money(fees)}</td><td class="num">${money(total - fees)}</td><td></td></tr></tfoot>` : '');
  };
  $('#wdFilter').addEventListener('submit', e => { e.preventDefault(); load().catch(x => toast(x.message, true)); });
  await load();
  if (isMgr()) return;
  $('#wdAdd').addEventListener('click', () => openWithdrawalForm(load).catch(x => toast(x.message, true)));
};

async function openWithdrawalForm(onSaved) {
  const [members, accts, prods] = await Promise.all([api('/api/members'), api('/api/accounts'), api('/api/products')]);
  const paying = accts.filter(a => a.type === 'ASSET' && a.active);
  const savingsProds = prods.filter(p => p.category === 'SAVINGS' && p.active).map(p => [p.id, `${p.productCode} – ${p.productDetails}`]);
  let formEl = null;
  const field = n => $(`[name=${n}]`, formEl);
  let balance = null;
  modal({
    title: 'Withdraw savings', submit: 'Save withdrawal',
    body: `<div class="grid3">${sel('memberId', 'Member *', memberOptions(members), { req: 1, blank: '— Select member —' })}` +
      `${inp('date', 'Date *', { type: 'date', req: 1, value: today() })}${inp('reference', 'Voucher / Reference', { ph: 'e.g. PV-001' })}</div>` +
      `<div id="wdBalance" class="hint">Select a member to see their savings balance.</div>` +
      `<div class="grid3">${ugx('amount', 'Amount withdrawn from savings *', { req: 1 })}${ugx('fee', 'Withdrawal fee')}` +
      `<div id="wdPay" class="hint" style="align-self:end">Paid out: UGX 0.00</div></div>` +
      `<div class="grid2">${sel('paidFromAccountId', 'Paid from (account) *', paying.map(a => [a.id, `${a.code} – ${a.name}`]), { req: 1, blank: '— Select account —' })}` +
      (savingsProds.length > 1 ? sel('savingsProductId', 'Savings product', savingsProds) : inp('notes', 'Notes', { ph: 'Optional' })) + `</div>` +
      (savingsProds.length > 1 ? inp('notes', 'Notes', { ph: 'Optional' }) : ''),
    onMount: form => {
      formEl = form;
      const cash = paying.find(a => a.code === '1100') || paying[0];
      if (cash) field('paidFromAccountId').value = cash.id;
      const showPay = () => { $('#wdPay').innerHTML = `Paid out: <strong>UGX ${money(Math.max(0, Number(field('amount').value || 0) - Number(field('fee').value || 0)))}</strong>`; };
      field('amount').addEventListener('input', showPay);
      field('fee').addEventListener('input', showPay);
      field('memberId').addEventListener('change', async () => {
        const id = field('memberId').value;
        balance = null;
        if (!id) { $('#wdBalance').textContent = 'Select a member to see their savings balance.'; return; }
        try {
          const st = await api(`/api/members/${id}/statement`);
          balance = Number(st.savingsBalance);
          $('#wdBalance').innerHTML = `Savings balance: <strong>UGX ${money(balance)}</strong>`;
        } catch (x) { $('#wdBalance').textContent = ''; }
      });
    },
    onSubmit: async () => {
      const amount = Number(field('amount').value || 0);
      const fee = Number(field('fee').value || 0);
      if (balance !== null && amount > balance) throw new Error(`The member only has UGX ${money(balance)} in savings.`);
      if (fee >= amount) throw new Error('The withdrawal fee must be less than the amount withdrawn.');
      const r = await api('/api/withdrawals', { method: 'POST', body: {
        memberId: Number(field('memberId').value), amount, fee, date: field('date').value, reference: field('reference').value,
        paidFromAccountId: Number(field('paidFromAccountId').value),
        savingsProductId: field('savingsProductId') && field('savingsProductId').value ? Number(field('savingsProductId').value) : null,
        notes: field('notes').value } });
      toast(`Withdrawal saved · UGX ${money(Number(r.amount) - Number(r.fee || 0))} paid out`);
      await onSaved();
    }
  });
}

/* ------------------------------------------------------------------ journal entries (manual, many lines) */
views.journals = async el => {
  const branches = isMgr() ? await api('/api/branches') : [];
  el.innerHTML = `<div class="panel"><form class="toolbar" id="jFilter">` +
    (isMgr() ? sel('branchId', 'Branch', branches.map(b => [b.id, `${b.code} – ${b.name}`]), { blank: 'All branches' }) : '') +
    inp('from', 'From', { type: 'date' }) + inp('to', 'To', { type: 'date' }) +
    `<button class="btn">Apply</button><span class="spacer"></span>` +
    (isMgr() ? '' : '<button type="button" class="btn primary" data-act="addJournal">+ New journal</button>') +
    `</form><p class="muted" style="margin-top:0">A journal can have any number of debit and credit lines, as long as they balance. ` +
    `Member savings, shares and loans are posted from the deposit, payroll and loan screens, not here.</p><div id="jOut"></div></div>`;
  const load = async () => {
    const f = fd($('#jFilter')), qs = new URLSearchParams();
    ['branchId', 'from', 'to'].forEach(k => { if (f[k]) qs.set(k, f[k]); });
    qs.set('limit', 500);
    const rows = await api('/api/journals?' + qs);
    const total = rows.reduce((a, r) => a + Number(r.totalAmount), 0);
    const cols = [
      { h: 'Journal no.', f: r => `<strong>${esc(r.journalNo)}</strong>` },
      { h: 'Date', f: r => esc(r.date) },
      ...(isMgr() ? [{ h: 'Branch', f: r => esc(r.branchCode) }] : []),
      { h: 'Reference', f: r => esc(r.reference || '') },
      { h: 'Narration', f: r => esc(r.narration || '') },
      { h: 'Total', num: 1, f: r => money(r.totalAmount) },
      { h: 'Entered by', f: r => esc(r.createdBy || '') },
      { h: '', f: r => rowBtns('journal', r.id) }
    ];
    $('#jOut').innerHTML = table(cols, rows, rows.length
      ? `<tfoot><tr><td colspan="${cols.length - 3}">Total (${rows.length} journals${rows.length >= 500 ? ', the latest only: choose dates to see others' : ''})</td><td class="num">${money(total)}</td><td colspan="2"></td></tr></tfoot>` : '');
  };
  $('#jFilter').addEventListener('submit', e => { e.preventDefault(); load().catch(x => toast(x.message, true)); });
  await load();
};

/** New journal (existing = null) or edit one. Any number of lines; debits must equal credits. */
async function journalModal(existing) {
  const [accts, blocked] = await Promise.all([api('/api/accounts'), api('/api/journals/blocked-accounts')]);
  const usable = accts.filter(a => a.active && !blocked.includes(a.id));
  const options = '<option value="">Select account…</option>' +
    usable.map(a => `<option value="${a.id}">${esc(a.code)} – ${esc(a.name)} (${esc(label(a.type))})</option>`).join('');
  const lineHtml = () => `<div class="jline"><select name="acct">${options}</select><input name="desc" placeholder="Line description">` +
    `<input name="dr" type="number" step="0.01" min="0" placeholder="Debit"><input name="cr" type="number" step="0.01" min="0" placeholder="Credit">` +
    `<button type="button" class="btn small danger" data-rm title="Remove line">✕</button></div>`;
  modal({
    title: existing ? `Edit journal ${existing.journalNo}` : 'New journal entry', submit: existing ? 'Save changes' : 'Post journal', wide: true,
    body: `<div class="row2">${inp('date', 'Date', { type: 'date', req: 1, value: existing ? existing.date : today() })}${inp('reference', 'Reference / Voucher no.')}</div>` +
      inp('narration', 'Narration') +
      `<div class="jline jhead"><span>Account</span><span>Description</span><span>Debit</span><span>Credit</span><span></span></div>` +
      `<div id="jLines" class="lines"></div><button type="button" class="btn small" id="jAdd">+ Add line</button>` +
      `<div id="jTotals" class="jtotals"></div>`,
    onMount: form => {
      const box = $('#jLines', form);
      const totals = () => {
        let dr = 0, cr = 0;
        box.querySelectorAll('.jline').forEach(l => { dr += Number($('[name=dr]', l).value || 0); cr += Number($('[name=cr]', l).value || 0); });
        const diff = Math.round(dr * 100) - Math.round(cr * 100);
        $('#jTotals', form).innerHTML = `<span>Debits <strong>${money(dr)}</strong></span><span>Credits <strong>${money(cr)}</strong></span>` +
          (diff === 0 && dr > 0 ? `<span class="ok-text">✓ Balanced</span>` : `<span class="warn-text">Difference ${money(Math.abs(diff) / 100)}</span>`);
      };
      const add = l => {
        box.insertAdjacentHTML('beforeend', lineHtml());
        const row = box.lastElementChild;
        if (l) {
          $('[name=acct]', row).value = l.accountId;
          $('[name=desc]', row).value = l.description || '';
          if (Number(l.debit)) $('[name=dr]', row).value = l.debit;
          if (Number(l.credit)) $('[name=cr]', row).value = l.credit;
        }
      };
      (existing ? existing.lines : [null, null]).forEach(add);
      $('#jAdd', form).onclick = () => { add(null); totals(); };
      form.addEventListener('click', e => { if (e.target.matches('[data-rm]') && box.children.length > 2) { e.target.closest('.jline').remove(); totals(); } });
      form.addEventListener('input', e => {
        // a line is either a debit or a credit
        if (e.target.name === 'dr' && Number(e.target.value) > 0) $('[name=cr]', e.target.closest('.jline')).value = '';
        if (e.target.name === 'cr' && Number(e.target.value) > 0) $('[name=dr]', e.target.closest('.jline')).value = '';
        totals();
      });
      totals();
    },
    onSubmit: async f => {
      const lines = [...f.querySelectorAll('#jLines .jline')].map(l => ({
        accountId: $('[name=acct]', l).value ? Number($('[name=acct]', l).value) : null, description: $('[name=desc]', l).value,
        debit: Number($('[name=dr]', l).value || 0), credit: Number($('[name=cr]', l).value || 0) }))
        .filter(l => l.accountId || l.debit || l.credit);
      if (lines.length < 2) throw new Error('A journal needs at least two lines.');
      if (lines.some(l => !l.accountId)) throw new Error('Choose an account on every line.');
      const dr = lines.reduce((a, l) => a + Math.round(l.debit * 100), 0), cr = lines.reduce((a, l) => a + Math.round(l.credit * 100), 0);
      if (dr !== cr) throw new Error(`The journal does not balance: debits ${money(dr / 100)} and credits ${money(cr / 100)} must be equal.`);
      const v = fd(f);
      const body = { date: v.date, reference: v.reference, narration: v.narration, lines };
      if (existing) await api(`/api/journals/${existing.id}`, { method: 'PUT', body });
      else await api('/api/journals', { method: 'POST', body });
      toast(existing ? 'Journal updated' : 'Journal posted'); route();
    }
  });
  if (existing) {
    const f = $('#modalRoot form');
    $('[name=reference]', f).value = existing.reference || '';
    $('[name=narration]', f).value = existing.narration || '';
  }
}

/* ------------------------------------------------------------------ interest computation (monthly, reducing balance) */
const interestCols = () => [
  { h: 'Loan', f: r => '#' + r.loanId },
  ...(isMgr() ? [{ h: 'Branch', f: r => esc(r.branchCode) }] : []),
  { h: 'Member', f: r => `${esc(r.memberNo)} · ${esc(r.memberName)}` },
  { h: 'Balance at start of month', num: 1, f: r => money(r.openingPrincipal) },
  { h: 'Balance at end of month', num: 1, f: r => money(r.closingPrincipal) },
  { h: 'Rate % p.a.', num: 1, f: r => r.ratePercent },
  { h: 'Interest', num: 1, f: r => `<strong>${money(r.interest)}</strong>` +
      (r.changed ? ' <span class="badge PENDING">repayments changed since posting</span>' : '') }
];
const interestFoot = (lines, span) => lines.length
  ? `<tfoot><tr><td colspan="${span}">Total interest on ${lines.length} loan${lines.length === 1 ? '' : 's'}</td><td class="num">${money(lines.reduce((a, r) => a + Number(r.interest), 0))}</td></tr></tfoot>` : '';

views.interest = async el => {
  const branches = isMgr() ? await api('/api/branches') : [];
  await loadFees();
  const last = new Date(); last.setDate(1); last.setMonth(last.getMonth() - 1);
  const defaultMonth = `${last.getFullYear()}-${String(last.getMonth() + 1).padStart(2, '0')}`;
  el.innerHTML = `<div class="panel"><form class="toolbar" id="intFilter">` +
    (isMgr() ? sel('branchId', 'Branch', branches.map(b => [b.id, `${b.code} – ${b.name}`]), { blank: 'All branches' }) : '') +
    inp('month', 'Month', { type: 'month', req: 1, value: defaultMonth }) +
    `<button class="btn primary">View interest</button></form>` +
    `<p class="muted" style="margin:0">Pick a month and click <strong>View interest</strong> to see each loan's balance and the interest it earned that month: ` +
    (FEES.dailyInterest
      ? `the yearly rate ÷ 365 for every day of the month (31 days in January, 28 in a normal February...) on the principal still owed at the end of the month. ` +
        `A loan is charged for the whole month it is paid out in, whatever day that is. `
      : `its monthly rate (yearly rate ÷ 12) on the principal still owed at the end of the month. Days are not counted: a loan paid out on the 1st ` +
        `and one paid out on the 20th are both charged a full month. `) +
    `Nothing is booked until you click <strong>Post interest</strong>, which books ` +
    `Dr Interest receivable, Cr Loan interest income, and adds the interest to the member's loan.</p></div>` +
    `<div class="panel" id="intOut"></div><div class="panel"><h3>Interest runs already posted</h3><div id="intRuns"></div></div>`;

  const loadRuns = async () => {
    const f = fd($('#intFilter'));
    const rows = await api('/api/interest-runs' + (f.branchId ? `?branchId=${f.branchId}` : ''));
    $('#intRuns').innerHTML = table([
      { h: 'Month', f: r => `<strong>${esc(r.month)}</strong>` },
      ...(isMgr() ? [{ h: 'Branch', f: r => esc(r.branchCode) }] : []),
      { h: 'Loans', num: 1, f: r => r.loanCount },
      { h: 'Interest', num: 1, f: r => money(r.totalInterest) },
      { h: 'Posted by', f: r => esc(r.createdBy || '') },
      { h: 'On', f: r => esc(String(r.createdAt).replace('T', ' ').slice(0, 16)) },
      { h: '', f: r => `<div class="actions"><button class="btn small" data-act="view" data-kind="run" data-id="${r.id}">View</button>` +
          (isMgr() ? '' : `<button class="btn small danger" data-act="del" data-kind="run" data-id="${r.id}">Delete</button>`) + `</div>` }
    ], rows);
  };

  const view = async () => {
    const f = fd($('#intFilter'));
    const qs = new URLSearchParams({ month: f.month });
    if (f.branchId) qs.set('branchId', f.branchId);
    const p = await api('/api/interest-runs/preview?' + qs);
    const cols = interestCols();
    let note = '';
    if (p.runsThisMonth.length) {
      note += `<div class="error" style="background:var(--blue-dark)">Interest for ${esc(p.month)} was already posted: ` +
        p.runsThisMonth.map(r => `${money(r.totalInterest)} on ${r.loanCount} loan${r.loanCount === 1 ? '' : 's'} (${esc(r.createdBy)})`).join('; ') +
        `. The loans below are the ones not charged yet.</div>`;
    }
    if (!p.ended) {
      note += `<div class="error" style="background:var(--blue-dark)">${esc(p.month)} has not ended yet, so this is what the interest would be. ` +
        `It can be posted after ${esc(p.monthEnd)}.</div>`;
    }
    const canPost = !isMgr() && p.ended && p.lines.length > 0;
    $('#intOut').innerHTML = `<h3>Interest for ${esc(p.month)} <span class="muted">· to ${esc(p.monthEnd)}</span></h3>${note}` +
      (p.lines.length ? table(cols, p.lines, interestFoot(p.lines, cols.length - 1))
        : `<div class="empty">${p.runsThisMonth.length ? 'Every loan out in this month has been charged.' : 'No loan was out in this month, so there is no interest to compute.'}</div>`) +
      (canPost ? `<div class="toolbar" style="margin-top:14px"><button class="btn primary" id="intPost">Post interest · ${money(p.total)}</button>` +
        `<span class="muted">This books the entries shown above. A loan can be charged only once for a month.</span></div>` : '');
    const post = $('#intPost');
    if (post) post.onclick = async () => {
      post.disabled = true;
      try {
        await api('/api/interest-runs', { method: 'POST', body: { month: p.month } });
        toast(`Interest for ${p.month} posted · UGX ${money(p.total)}`);
        await view(); await loadRuns();
      } catch (x) { toast(x.message, true); post.disabled = false; }
    };
  };

  $('#intFilter').addEventListener('submit', e => { e.preventDefault(); Promise.all([view(), loadRuns()]).catch(x => toast(x.message, true)); });
  await Promise.all([view(), loadRuns()]);
};

/* ------------------------------------------------------------------ savings interest (daily, booked each month, paid into savings at year end) */
views.savingsinterest = async el => {
  const info = await api('/api/savings-interest/info');
  const last = new Date(); last.setDate(1); last.setMonth(last.getMonth() - 1);
  const defaultMonth = `${last.getFullYear()}-${String(last.getMonth() + 1).padStart(2, '0')}`;
  const manager = isMgr();
  el.innerHTML = `<div class="panel"><h3>Interest on savings</h3>` +
    (info.enabled
      ? `<p class="muted" style="margin-top:0">Savings earn <strong>${esc(info.ratePercent)}% a year</strong>, worked out every day on the member's balance (the yearly rate ÷ ${esc(info.dayBasis)}). ` +
        `Money counts from the day after it is paid in and stops the day after it is taken out. The interest of a month is booked <strong>by itself</strong> ` +
        `once the month has ended (starting with ${esc(info.firstMonth)}) and is kept as owed to the member. At year end you pay it into their savings below.</p>`
      : `<p class="muted">Savings interest is not switched on.</p>`) + `</div>` +
    `<div class="panel"><form class="toolbar" id="svFilter">` + inp('month', 'Month', { type: 'month', req: 1, value: defaultMonth }) +
    `<button class="btn primary">View interest</button></form><div id="svOut"></div></div>` +
    `<div class="panel"><h3>Pay the interest into savings</h3><form class="toolbar" id="svPayFilter">` + inp('date', 'Date of payment', { type: 'date', req: 1, value: today() }) +
    `<button class="btn primary">View interest owed</button></form><div id="svPay"></div></div>` +
    `<div class="panel"><h3>Savings interest booked so far</h3><div id="svRuns"></div></div>`;

  const loadRuns = async () => {
    const rows = await api('/api/savings-interest/runs');
    $('#svRuns').innerHTML = table([
      { h: 'What', f: r => r.kind === 'PAYOUT' ? 'Paid into savings' : 'Interest earned' },
      { h: 'For', f: r => `<strong>${esc(r.period)}</strong>` },
      { h: 'Members', num: 1, f: r => r.memberCount },
      { h: 'Amount', num: 1, f: r => money(r.totalAmount) },
      { h: 'Booked by', f: r => esc(r.createdBy || '') },
      { h: 'On', f: r => esc(String(r.createdAt).replace('T', ' ').slice(0, 16)) },
      { h: '', f: r => manager ? '' : `<button class="btn small danger" data-svdel="${r.id}">Delete</button>` }
    ], rows);
    el.querySelectorAll('[data-svdel]').forEach(b => b.onclick = async () => {
      if (!confirm('Delete this booking? Its entries are taken out of the member and general ledgers. Only the latest booking can be deleted.')) return;
      try { await api(`/api/savings-interest/runs/${b.dataset.svdel}`, { method: 'DELETE' }); toast('Deleted'); await Promise.all([view(), loadRuns(), viewOwed()]); }
      catch (x) { toast(x.message, true); }
    });
  };

  const view = async () => {
    const f = fd($('#svFilter'));
    const p = await api('/api/savings-interest/preview?' + new URLSearchParams({ month: f.month }));
    let note = '';
    if (p.booked) note += `<div class="error" style="background:var(--blue-dark)">Interest for ${esc(p.month)} has already been booked.</div>`;
    else if (!p.ended) note += `<div class="error" style="background:var(--blue-dark)">${esc(p.month)} has not ended yet, so this is what the interest would be so far. It is booked after ${esc(p.monthEnd)}.</div>`;
    const canPost = !manager && info.enabled && p.ended && !p.booked && p.lines.length > 0;
    $('#svOut').innerHTML = `<h3>Interest for ${esc(p.month)} <span class="muted">· to ${esc(p.monthEnd)}</span></h3>${note}` +
      (p.lines.length ? table([
        { h: 'Member', f: r => `${esc(r.memberNo)} · ${esc(r.memberName)}` },
        { h: 'Savings at start of month', num: 1, f: r => money(r.openingBalance) },
        { h: 'Average savings in the month', num: 1, f: r => money(r.averageBalance) },
        { h: 'Interest', num: 1, f: r => `<strong>${money(r.interest)}</strong>` }
      ], p.lines, `<tfoot><tr><td colspan="3">Total interest for ${p.lines.length} member${p.lines.length === 1 ? '' : 's'}</td><td class="num">${money(p.total)}</td></tr></tfoot>`)
        : `<div class="empty">${info.enabled && p.month < info.firstMonth ? `Interest before ${esc(info.firstMonth)} was worked out in the old system.` : 'No savings earned interest in this month.'}</div>`) +
      (canPost ? `<div class="toolbar" style="margin-top:14px"><button class="btn primary" id="svPost">Book interest · ${money(p.total)}</button>` +
        `<span class="muted">Dr Savings interest expense, Cr Savings interest payable, and owed to each member.</span></div>` : '');
    const post = $('#svPost');
    if (post) post.onclick = async () => {
      post.disabled = true;
      try { await api('/api/savings-interest/runs', { method: 'POST', body: { month: p.month } }); toast(`Interest for ${p.month} booked · UGX ${money(p.total)}`); await Promise.all([view(), loadRuns(), viewOwed()]); }
      catch (x) { toast(x.message, true); post.disabled = false; }
    };
  };

  const viewOwed = async () => {
    const f = fd($('#svPayFilter'));
    const o = await api('/api/savings-interest/owed?' + new URLSearchParams({ date: f.date }));
    $('#svPay').innerHTML = (o.members.length ? table([
      { h: 'Member', f: r => `${esc(r.memberNo)} · ${esc(r.memberName)}` },
      { h: 'Interest owed', num: 1, f: r => money(r.amount) }
    ], o.members, `<tfoot><tr><td>Total owed to ${o.members.length} member${o.members.length === 1 ? '' : 's'}</td><td class="num">${money(o.total)}</td></tr></tfoot>`)
      : '<div class="empty">No member is owed savings interest on this date.</div>') +
      (!manager && info.enabled && o.members.length ? `<div class="toolbar" style="margin-top:14px"><button class="btn primary" id="svPayBtn">Pay ${money(o.total)} into savings</button>` +
        `<span class="muted">Each member's savings go up by what they are owed, and what is owed goes down to nothing.</span></div>` : '');
    const b = $('#svPayBtn');
    if (b) b.onclick = async () => {
      if (!confirm(`Pay UGX ${money(o.total)} of interest into the savings of ${o.members.length} members, dated ${o.date}?`)) return;
      b.disabled = true;
      try { await api('/api/savings-interest/payout', { method: 'POST', body: { date: o.date } }); toast('Interest paid into savings'); await Promise.all([viewOwed(), loadRuns()]); }
      catch (x) { toast(x.message, true); b.disabled = false; }
    };
  };

  $('#svFilter').addEventListener('submit', e => { e.preventDefault(); view().catch(x => toast(x.message, true)); });
  $('#svPayFilter').addEventListener('submit', e => { e.preventDefault(); viewOwed().catch(x => toast(x.message, true)); });
  await Promise.all([view(), loadRuns(), viewOwed()]);
};

function lineHtml(members) {
  return `<div class="line">` +
    `<label>Member<select name="member" required><option value="">Select…</option>${memberOptions(members).map(([v, t]) => `<option value="${v}">${esc(t)}</option>`).join('')}</select></label>` +
    `<label>Savings<input name="savings" type="number" min="0" step="0.01"></label>` +
    `<label>Shares<input name="shares" type="number" min="0" step="0.01"></label>` +
    `<label>Loan repayment<input name="repay" type="number" min="0" step="0.01"></label>` +
    `<label>Loan<select name="loan"><option value="">—</option></select></label>` +
    `<button type="button" class="btn small danger" data-rm title="Remove line">✕</button></div>`;
}

/* ------------------------------------------------------------------ actions (buttons inside views) */
/* ------------------------------------------------------------------ view / edit / delete of every record */
function infoModal(title, html, wide) {
  const root = $('#modalRoot');
  root.innerHTML = `<div class="overlay"><div class="modal ${wide ? 'wide' : ''}"><h3>${esc(title)}</h3>${html}` +
    `<div class="modal-foot"><button class="btn primary" data-close>Close</button></div></div></div>`;
  const close = () => { root.innerHTML = ''; };
  $('[data-close]', root).onclick = close;
  $('.overlay', root).addEventListener('mousedown', e => { if (e.target.classList.contains('overlay')) close(); });
}
const kvHtml = pairs => `<div class="kv">${pairs.map(([k, v]) => `<div><span>${esc(k)}</span>${esc(v === undefined || v === null || v === '' ? '—' : v)}</div>`).join('')}</div>`;
const PAID_INTO = { SAVINGS_DEPOSIT: 'Savings', SHARE_PURCHASE: 'Shares', LOAN_REPAYMENT: 'Loan repayment' };

const viewers = {
  async entry(id) {
    const e = await api(`/api/entries/${id}`);
    const gd = e.generalLedger.reduce((a, g) => a + Number(g.debit), 0), gc = e.generalLedger.reduce((a, g) => a + Number(g.credit), 0);
    infoModal(`${label(e.type)} · ${e.memberNo}`,
      kvHtml([['Date', e.date], ['Member', `${e.memberNo} · ${e.memberName}`], ['Type', label(e.type)],
        ['Receiving account', e.receivingAccount || label(e.source)],
        ...(e.type === 'SAVINGS_WITHDRAWAL' ? [['Amount withdrawn', money(e.amount)], ['Withdrawal fee', money(e.fee)], ['Paid out', money(Number(e.amount) - Number(e.fee || 0))]] : []),
        ['Reference', e.reference], ['Notes', e.notes], ['Entered by', e.createdBy]]) +
      (e.lockedReason ? `<p class="muted"><em>${esc(e.lockedReason)}</em></p>` : '') +
      `<h4 class="sect">Member ledger${e.depositGroup ? ' (whole deposit)' : ''}</h4>` + table([
        { h: 'Date', f: r => esc(r.date) }, ...(S.single ? [] : [{ h: 'Branch', f: r => esc(r.branchCode) }]), { h: 'Product ID', f: r => esc(r.productCode) }, { h: 'Description', f: r => esc(r.description || '') },
        { h: 'Debit', num: 1, f: r => Number(r.debit) ? money(r.debit) : '' }, { h: 'Credit', num: 1, f: r => Number(r.credit) ? money(r.credit) : '' }
      ], e.memberLedger) +
      `<h4 class="sect">General ledger${e.depositGroup ? ' (whole deposit)' : ''}</h4>` + table([
        ...(S.single ? [] : [{ h: 'Branch', f: r => esc(r.branchCode) }]), { h: 'Account', f: r => `<strong>${esc(r.accountCode)}</strong> ${esc(r.accountName)}` }, { h: 'Description', f: r => esc(r.description || '') },
        { h: 'Debit', num: 1, f: r => Number(r.debit) ? money(r.debit) : '' }, { h: 'Credit', num: 1, f: r => Number(r.credit) ? money(r.credit) : '' }
      ], e.generalLedger, `<tfoot><tr><td colspan="${S.single ? 2 : 3}">Total</td><td class="num">${money(gd)}</td><td class="num">${money(gc)}</td></tr></tfoot>`), true);
  },
  async batch(id) {
    const v = await api(`/api/payroll/${id}`), b = v.batch;
    infoModal(`Payroll ${b.period} · ${b.employer}`,
      kvHtml([['Date', b.paymentDate], ['Period', b.period], ['Employer', b.employer], ['Receiving account', b.receivingAccount],
        ['Members', b.lineCount], ['Total deducted', money(b.totalAmount)], ['Posted by', b.createdBy]]) +
      `<h4 class="sect">Deductions</h4>` + table([
        { h: 'Member', f: r => `${esc(r.memberNo)} · ${esc(r.memberName)}` },
        { h: 'Paid into', f: r => esc(PAID_INTO[r.type] || label(r.type)) + (r.productCode ? ` <span class="muted">· ${esc(r.productCode)}</span>` : '') },
        { h: 'Amount', num: 1, f: r => money(r.amount) }
      ], v.entries), true);
  },
  async loan(id) {
    const v = await api(`/api/loans/${id}/view`), l = v.loan;
    const gd = v.generalLedger.reduce((a, r) => a + Number(r.debit), 0), gc = v.generalLedger.reduce((a, r) => a + Number(r.credit), 0);
    infoModal(`Loan #${l.id} · ${l.memberName}`,
      kvHtml([['Loan date', l.appliedOn], ['Status', label(l.status)], ['Disbursed', l.disbursedOn], ['Principal', money(l.principal)],
        ['Interest % p.a.', l.annualRatePercent], ['Interest method', l.interestMethod === 'REDUCING_BALANCE' ? 'Reducing balance' : 'Straight-line'],
        ['Months', l.termMonths], ['Monthly installment', money(l.installment)], ['Loan process fee', money(l.processFee || 0)],
        ['Insurance', money(l.insuranceFee || 0)], ['Loan application fee', money(l.applicationFee)], ['Legal charges', money(l.legalCharges || 0)],
        ['Total due', money(l.totalDue)], ['Interest charged so far', money(l.interestCharged)], ['Repaid', money(l.repaid)],
        ['Outstanding (books)', money(l.outstanding)], ['Still to pay (with future interest)', money(l.remaining)],
        ...(l.topUpOfLoanId ? [['Top-up of loan', '#' + l.topUpOfLoanId], ['Cleared on that loan at payout', l.topUpSettled == null ? 'at payout' : money(l.topUpSettled)]] : [])]) +
      `<h4 class="sect">Payout and repayments</h4>` + table([
        { h: 'Date', f: r => esc(r.date) }, { h: 'Type', f: r => esc(label(r.type)) },
        { h: 'Receiving account', f: r => esc(r.receivingAccount || label(r.source)) }, { h: 'Reference', f: r => esc(r.reference || '') },
        { h: 'Amount', num: 1, f: r => money(r.amount) }
      ], v.entries) +
      // what the payout and the repayments did to the books, as on a transaction's own view
      (v.memberLedger.length || v.generalLedger.length
        ? `<h4 class="sect">Member ledger</h4>` + table([
            { h: 'Date', f: r => esc(r.date) }, { h: 'Product ID', f: r => esc(r.productCode) }, { h: 'Description', f: r => esc(r.description || '') },
            { h: 'Debit', num: 1, f: r => Number(r.debit) ? money(r.debit) : '' }, { h: 'Credit', num: 1, f: r => Number(r.credit) ? money(r.credit) : '' }
          ], v.memberLedger, '', true) +
          `<h4 class="sect">General ledger</h4>` + table([
            { h: 'Date', f: r => esc(r.date) }, { h: 'Account', f: r => `<strong>${esc(r.accountCode)}</strong> ${esc(r.accountName)}` }, { h: 'Description', f: r => esc(r.description || '') },
            { h: 'Debit', num: 1, f: r => Number(r.debit) ? money(r.debit) : '' }, { h: 'Credit', num: 1, f: r => Number(r.credit) ? money(r.credit) : '' }
          ], v.generalLedger, `<tfoot><tr><td colspan="3">Total</td><td class="num">${money(gd)}</td><td class="num">${money(gc)}</td></tr></tfoot>`, true)
        : `<p class="muted">Nothing has been posted to the ledgers by this system for this loan yet. A loan brought forward from the old system has its history in the member ledger.</p>`), true);
  },
  async schedule(id) {
    const doc = await api(`/api/loans/${id}/schedule`);
    infoModal(doc.title, `<div class="actions" style="margin-bottom:12px"><button class="btn" id="schOpen">Open PDF</button><button class="btn" id="schDownload">Download PDF</button></div>` +
      renderDoc(doc), true);
    const path = `/api/loans/${id}/schedule/pdf`;
    $('#schOpen').onclick = async () => {
      const w = window.open('', '_blank');
      if (!w) { toast('Allow pop-ups for this site to open the PDF', true); return; }
      w.document.write('<p style="font-family:sans-serif">Preparing PDF…</p>');
      try { w.location.href = await pdfUrl(path); } catch (x) { w.close(); toast(x.message, true); }
    };
    $('#schDownload').onclick = async () => {
      try {
        const a = document.createElement('a');
        a.href = await pdfUrl(path);
        a.download = `loan-${id}-schedule.pdf`;
        document.body.appendChild(a); a.click(); a.remove();
      } catch (x) { toast(x.message, true); }
    };
  },
  async run(id) {
    const v = await api(`/api/interest-runs/${id}`), r = v.run;
    const cols = interestCols();
    infoModal(`Interest run ${r.month}${S.single ? '' : ' · ' + r.branchCode}`,
      kvHtml([['Month', r.month], ...(S.single ? [] : [['Branch', r.branchCode]]), ['Loans', r.loanCount], ['Total interest', money(r.totalInterest)], ['Computed by', r.createdBy]]) +
      `<h4 class="sect">Interest charged</h4>` + table(cols, v.lines, interestFoot(v.lines, cols.length - 1)), true);
  },
  member(id) { location.hash = `#statement/${id}`; },
  async journal(id) {
    const j = await api(`/api/journals/${id}`);
    const dr = j.lines.reduce((a, l) => a + Number(l.debit), 0), cr = j.lines.reduce((a, l) => a + Number(l.credit), 0);
    infoModal(`Journal ${j.journalNo}`,
      kvHtml([['Journal no.', j.journalNo], ['Date', j.date], ...(S.single ? [] : [['Branch', j.branchCode]]), ['Reference', j.reference],
        ['Narration', j.narration], ['Total', money(j.totalAmount)], ['Entered by', j.createdBy]]) +
      `<h4 class="sect">Lines</h4>` + table([
        { h: 'Account', f: r => `<strong>${esc(r.accountCode)}</strong> ${esc(r.accountName)}` },
        { h: 'Description', f: r => esc(r.description || '') },
        { h: 'Debit', num: 1, f: r => Number(r.debit) ? money(r.debit) : '' },
        { h: 'Credit', num: 1, f: r => Number(r.credit) ? money(r.credit) : '' }
      ], j.lines, `<tfoot><tr><td colspan="2">Total</td><td class="num">${money(dr)}</td><td class="num">${money(cr)}</td></tr></tfoot>`) +
      // the member's side of it: the loan, savings or shares the journal moved
      (j.memberLedger && j.memberLedger.length ? `<h4 class="sect">Member ledger details</h4>` + table([
        { h: 'Member', f: r => `<strong>${esc(r.memberNo)}</strong> ${esc(r.memberName)}` },
        { h: 'Product', f: r => `${esc(r.productCode)} · ${esc(r.productName)}` },
        { h: 'Description', f: r => esc(r.description || '') },
        { h: 'Debit', num: 1, f: r => Number(r.debit) ? money(r.debit) : '' },
        { h: 'Credit', num: 1, f: r => Number(r.credit) ? money(r.credit) : '' }
      ], j.memberLedger, '', true) : ''), true);
  },
  async account(id) {
    const a = (await api('/api/accounts')).find(x => String(x.id) === String(id));
    if (!a) return;
    infoModal(`${a.code} · ${a.name}`, kvHtml([['Type', label(a.type)], ['Under', a.parentName], ['Description', a.description],
      ['Standard account', a.system ? 'Yes' : 'No'], ['Status', a.active ? 'Active' : 'Inactive']]));
  },
  async product(id) {
    const p = (await api('/api/products')).find(x => String(x.id) === String(id));
    if (!p) return;
    infoModal(`${p.productCode} · ${p.productDetails}`, kvHtml([...(S.single ? [] : [['Branch', p.branchCode]]), ['Category', label(p.category)], ['Chart account', `${p.accountCode} ${p.productDetails}`],
      ['Remarks', p.remarks], ['Interest product', p.interestProduct], ['Interest income account', p.interestIncomeAccount], ['Status', p.active ? 'Active' : 'Inactive']]));
  }
};

const assetOptions = accts => accts.filter(a => a.type === 'ASSET' && a.active).map(a => [a.id, `${a.code} – ${a.name}`]);
const SOURCE_CODE = { CASH: '3020', BANK: '3040', MOBILE_MONEY: '3046' };   // CPA's cash, bank and mobile money accounts

const editors = {
  async entry(id) {
    const [e, accts, prods] = await Promise.all([api(`/api/entries/${id}`), api('/api/accounts'), api('/api/products')]);
    if (e.lockedReason) { toast(e.lockedReason, true); return; }
    const cat = { SAVINGS_DEPOSIT: 'SAVINGS', SAVINGS_WITHDRAWAL: 'SAVINGS', SHARE_PURCHASE: 'SHARES', SHARE_REFUND: 'SHARES' }[e.type];
    const prodList = cat ? prods.filter(p => p.category === cat && p.active) : [];
    modal({
      title: `Edit ${label(e.type).toLowerCase()} · ${e.memberNo}`, submit: 'Save changes',
      body: `<p class="muted">${esc(e.memberName)}. The member and type cannot be changed. Saving re-posts the ledger lines.</p>` +
        `<div class="row2">${inp('amount', 'Amount', { type: 'number', step: '0.01', min: '0.01', req: 1, value: e.amount })}${inp('date', 'Date', { type: 'date', req: 1, value: e.date })}</div>` +
        (e.type === 'SAVINGS_WITHDRAWAL' ? inp('fee', 'Withdrawal fee', { type: 'number', step: '0.01', min: '0', value: e.fee || 0 }) : '') +
        sel('receivingAccountId', 'Receiving account', assetOptions(accts), { req: 1 }) +
        (prodList.length > 1 ? sel('productId', 'Product', prodList.map(p => [p.id, `${p.productCode} – ${p.productDetails}`])) : '') +
        `<div class="row2">${inp('reference', 'Receipt / Reference', { value: e.reference || '' })}${inp('notes', 'Notes', { value: e.notes || '' })}</div>`,
      onMount: form => {
        const acc = e.receivingAccountId || (accts.find(a => a.code === SOURCE_CODE[e.source]) || {}).id;
        if (acc) $('select[name=receivingAccountId]', form).value = acc;
        if (prodList.length > 1 && e.productId) $('select[name=productId]', form).value = e.productId;
      },
      onSubmit: async f => {
        const v = fd(f);
        await api(`/api/entries/${id}`, { method: 'PUT', body: { amount: Number(v.amount), date: v.date, reference: v.reference, notes: v.notes,
          fee: e.type === 'SAVINGS_WITHDRAWAL' ? Number(v.fee || 0) : null,
          receivingAccountId: Number(v.receivingAccountId), productId: v.productId ? Number(v.productId) : null } });
        toast('Transaction updated'); route();
      }
    });
  },
  async batch(id) {
    const [v, accts] = await Promise.all([api(`/api/payroll/${id}`), api('/api/accounts')]);
    const b = v.batch;
    modal({
      title: `Edit payroll ${b.period} · ${b.employer}`, submit: 'Save changes',
      body: `<p class="muted">Changes the batch details for all ${b.lineCount} deductions and re-posts their ledger lines. To change an amount, delete the batch and post it again.</p>` +
        inp('employer', 'Employer', { req: 1, value: b.employer }) +
        `<div class="row2">${inp('period', 'Month', { type: 'month', req: 1, value: b.period })}${inp('date', 'Transaction date', { type: 'date', req: 1, value: b.paymentDate || today() })}</div>` +
        sel('receivingAccountId', 'Receiving account', assetOptions(accts), { req: 1 }),
      onMount: form => {
        const acc = b.receivingAccountId || (accts.find(a => a.code === '1200') || {}).id;
        if (acc) $('select[name=receivingAccountId]', form).value = acc;
      },
      onSubmit: async f => {
        const x = fd(f);
        await api(`/api/payroll/${id}`, { method: 'PUT', body: { employer: x.employer, period: x.period, date: x.date, receivingAccountId: Number(x.receivingAccountId) } });
        toast('Payroll batch updated'); route();
      }
    });
  },
  async loan(id) {
    const l = await api(`/api/loans/${id}`);
    if (l.status !== 'PENDING') { toast('Only a pending loan can be edited. If an active loan is wrong, delete it and enter it again.', true); return; }
    await loadFees();
    modal({
      title: `Edit loan #${l.id} · ${l.memberName}`, submit: 'Save changes',
      body: `<div class="row2">${inp('loanDate', 'Loan date', { type: 'date', req: 1, value: l.appliedOn })}${inp('termMonths', 'Term (months)', { type: 'number', min: '1', max: '600', req: 1, value: l.termMonths })}</div>` +
        `<div class="row2">${inp('principal', 'Principal amount', { type: 'number', step: '0.01', min: '0.01', req: 1, value: l.principal })}${inp('annualRatePercent', `Interest % per year (${l.interestMethod === 'REDUCING_BALANCE' ? 'reducing balance' : 'straight-line'})`, { type: 'number', step: '0.01', min: '0', req: 1, value: l.annualRatePercent })}</div>` +
        loanFeeFields(l) +
        `<div id="loanPreview" class="jtotals"></div>`,
      onMount: form => { loanPreview(form, l.interestMethod); wireLoanFees(form, 0); },
      onSubmit: async f => {
        const v = fd(f);
        await api(`/api/loans/${id}`, { method: 'PUT', body: { principal: Number(v.principal), annualRatePercent: Number(v.annualRatePercent),
          termMonths: Number(v.termMonths), ...feeBody(v), loanDate: v.loanDate || null } });
        toast('Loan updated'); route();
      }
    });
  },
  async member(id) {
    const m = (await api('/api/members')).find(x => String(x.id) === String(id));
    if (!m) { toast('Member not found', true); return; }
    modal({
      title: `Edit member ${m.memberNo}`, submit: 'Save changes',
      body: `<div class="row2">${inp('memberNo', 'Member no.', { req: 1, value: m.memberNo })}${inp('fullName', 'Full name', { req: 1, value: m.fullName })}</div>` +
        `<div class="row2">${inp('phone', 'Phone', { value: m.phone || '' })}${inp('nationalId', 'National ID', { value: m.nationalId || '' })}</div>` +
        `<div class="row2">${inp('employer', 'Employer', { value: m.employer || '' })}${inp('payrollNo', 'Payroll no.', { value: m.payrollNo || '' })}</div>`,
      onSubmit: async f => { await api(`/api/members/${id}`, { method: 'PUT', body: fd(f) }); toast('Member updated'); route(); }
    });
  },
  async journal(id) { return journalModal(await api(`/api/journals/${id}`)); },
  account(id) { return actions.editAccount({ id: String(id) }); },
  product(id) { return actions.editProduct({ id: String(id) }); }
};

const DELETE_INFO = { entry: ['transaction', '/api/entries/'], batch: ['payroll batch', '/api/payroll/'], loan: ['loan', '/api/loans/'],
  member: ['member', '/api/members/'], account: ['account', '/api/accounts/'], product: ['product', '/api/products/'],
  journal: ['journal entry', '/api/journals/'], run: ['interest run', '/api/interest-runs/'] };

function confirmDelete(kind, id) {
  const [noun, url] = DELETE_INFO[kind];
  const ledgers = ['entry', 'batch', 'loan', 'journal', 'run'].includes(kind)
    ? ' Its member-ledger and general-ledger lines are removed with it, so the books stay balanced.' : '';
  modal({
    title: `Delete this ${noun}?`, submit: 'Yes, delete',
    body: `<p>This cannot be undone.${ledgers} It is recorded in the audit trail.</p>`,
    onSubmit: async () => { await api(url + id, { method: 'DELETE' }); toast(`${label(noun)} deleted`); route(); }
  });
}

/* ------------------------------------------------------------------ audit trail */
/* ------------------------------------------------------------------ messages (SMS to members) */
const MSG_STATUS = { SENT: 'Sent', TEST: 'Test (not sent)', FAILED: 'Failed', NO_NUMBER: 'No mobile number', PENDING: 'Waiting' };
const when = t => t ? new Date(t).toLocaleString('en-GB', { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '';
/** Roughly how many SMS a text takes, for the counter while typing (the preview gives the exact count per member). */
const smsParts = t => t.length <= 160 ? 1 : Math.ceil(t.length / 153);

views.messages = async el => {
  const [cfg, members] = await Promise.all([api('/api/messages/settings'), isMgr() ? Promise.resolve([]) : api('/api/members')]);
  const employers = [...new Set(members.map(m => (m.employer || '').trim()).filter(Boolean))].sort();
  const mode = cfg.live
    ? `<div class="panel"><strong>Live:</strong> messages are sent by SMS through Africa's Talking${cfg.senderId ? `, as ${esc(cfg.senderId)}` : ''}` +
      `${cfg.sandbox ? ' (sandbox: to their simulator only, not to real phones)' : ''}. Each SMS uses credit.</div>`
    : `<div class="panel"><strong>Test mode:</strong> messages are prepared and kept in the history below, but no SMS is sent and no credit is used. ` +
      `Real sending starts once the SMS account details are added to the server settings.</div>`;
  el.innerHTML = mode + (isMgr() ? '' :
    `<div class="panel"><h3>New message</h3><form id="msgForm">` +
    `<div class="row2">${sel('audience', 'Send to', [['ALL', 'All members'], ['LOAN', 'Members with a loan'], ['EMPLOYER', 'Members of one employer'], ['CHOSEN', 'Chosen members']], { req: 1 })}` +
    `<div id="msgEmployer" hidden>${sel('employer', 'Employer', employers.map(e => [e, e]), { blank: '— Select —' })}</div></div>` +
    `<div id="msgChosen" hidden>${inp('find', 'Find members', { ph: 'Type a name or number, then tick the members below' })}` +
    `<div id="msgPick" class="tbl-wrap" style="max-height:260px;overflow:auto"></div><p class="muted" id="msgPicked" style="margin:4px 0 0">None chosen</p></div>` +
    `<label>Message<textarea name="text" rows="5" maxlength="900" required placeholder="Dear {name}, the AGM will be held on ..."></textarea></label>` +
    `<p class="muted" style="margin:4px 0" id="msgCount">0 characters · 1 SMS per member</p>` +
    `<p class="muted" style="margin:0 0 8px">Each member's own details can go in: <strong>{name}</strong>, <strong>{memberNo}</strong>, ` +
    `<strong>{savings}</strong>, <strong>{shares}</strong>, <strong>{loan}</strong>.</p>` +
    `<div class="toolbar"><button type="button" class="btn" id="msgPreview">Preview</button><button class="btn primary">${cfg.live ? 'Send' : 'Send (test)'}</button></div>` +
    `</form><div id="msgOut"></div></div>`) +
    `<div class="panel"><h3>Sent messages</h3><div id="msgHistory"></div></div>`;

  const loadHistory = async () => {
    const rows = await api('/api/messages');
    $('#msgHistory').innerHTML = table([
      { h: 'When', f: r => esc(when(r.createdAt)) },
      { h: 'By', f: r => esc(r.createdBy || '') },
      { h: 'To', f: r => esc(r.audience) },
      { h: 'Message', f: r => esc(r.text.length > 80 ? r.text.slice(0, 80) + '…' : r.text) },
      { h: 'Sent', num: 1, f: r => r.status === 'SENDING' ? 'sending…' : String(r.sent) },
      { h: 'Not sent', num: 1, f: r => String(r.failed) },
      { h: '', f: r => `${r.testMode ? '<span class="badge">Test</span> ' : ''}<button class="btn small" data-msg="${r.id}">View</button>` }
    ], rows);
    $('#msgHistory').querySelectorAll('[data-msg]').forEach(b => b.onclick = () => showBatch(b.dataset.msg).catch(x => toast(x.message, true)));
    // while a message is still going out, look again shortly
    if (rows.some(r => r.status === 'SENDING')) setTimeout(() => { if ($('#msgHistory')) loadHistory().catch(() => {}); }, 3000);
  };
  const showBatch = async id => {
    const d = await api('/api/messages/' + id);
    modal({
      title: 'Message details', submit: 'Close', wide: true,
      body: `<p><strong>${esc(d.batch.audience)}</strong> · ${esc(when(d.batch.createdAt))} by ${esc(d.batch.createdBy || '')}` +
        `${d.batch.testMode ? ' · <span class="badge">Test mode: not sent</span>' : ''}</p><p class="muted">${esc(d.batch.text)}</p>` +
        table([
          { h: 'No', f: r => esc(r.memberNo || '') },
          { h: 'Member', f: r => esc(r.memberName || '') },
          { h: 'Phone', f: r => esc(r.phone || '') },
          { h: 'Result', f: r => `<span class="badge ${r.status === 'SENT' ? 'in' : r.status === 'TEST' ? '' : 'out'}">${esc(MSG_STATUS[r.status] || r.status)}</span>` +
            (r.error ? ` <span class="muted">${esc(r.error)}</span>` : '') },
          { h: 'Cost', f: r => esc(r.cost || '') }
        ], d.recipients),
      onSubmit: async () => {}
    });
  };
  await loadHistory();
  if (isMgr()) return;

  const form = $('#msgForm');
  const audienceSel = $('select[name=audience]', form), text = $('textarea[name=text]', form);
  const chosen = new Set();
  const drawPick = () => {
    const q = (fd(form).find || '').trim().toLowerCase();
    const list = members.filter(m => !q || m.fullName.toLowerCase().includes(q) || String(m.memberNo).toLowerCase().includes(q)).slice(0, 200);
    $('#msgPick').innerHTML = list.length ? `<table><tbody>${list.map(m => `<tr><td style="width:32px"><input type="checkbox" data-id="${m.id}"${chosen.has(m.id) ? ' checked' : ''}></td>` +
      `<td>${esc(m.memberNo)}</td><td>${esc(m.fullName)}</td><td class="muted">${esc(m.phone || 'no phone')}</td></tr>`).join('')}</tbody></table>`
      : '<div class="empty">No member matches.</div>';
    $('#msgPick').querySelectorAll('input[type=checkbox]').forEach(c => c.onchange = () => {
      const id = Number(c.dataset.id);
      if (c.checked) chosen.add(id); else chosen.delete(id);
      $('#msgPicked').textContent = chosen.size ? `${chosen.size} chosen` : 'None chosen';
    });
  };
  audienceSel.onchange = () => {
    $('#msgEmployer').hidden = audienceSel.value !== 'EMPLOYER';
    $('#msgChosen').hidden = audienceSel.value !== 'CHOSEN';
    if (audienceSel.value === 'CHOSEN') drawPick();
  };
  $('input[name=find]', form).addEventListener('input', drawPick);
  $('input[name=find]', form).addEventListener('keydown', e => { if (e.key === 'Enter') e.preventDefault(); });
  text.addEventListener('input', () => {
    $('#msgCount').textContent = `${text.value.length} characters · ${smsParts(text.value)} SMS per member (more if the details make it longer)`;
  });
  const body = () => {
    const f = fd(form);
    return { audience: f.audience, employer: f.employer || null, memberIds: [...chosen], text: f.text };
  };
  const preview = async () => {
    const p = await api('/api/messages/preview', { method: 'POST', body: body() });
    $('#msgOut').innerHTML = `<div class="rep-totals">` +
      [['Members', p.members], ['Will get it', p.withNumber], ['No mobile number', p.members - p.withNumber], ['SMS in all', p.totalSms]]
        .map(([k, v]) => `<div class="rep-total"><span>${k}</span><strong>${v}</strong></div>`).join('') + `</div>` +
      (p.samples.length ? `<h4 class="sect">What members will see</h4>` + table([
        { h: 'Member', f: r => esc(`${r.memberNo} – ${r.name}`) }, { h: 'Phone', f: r => esc(r.phone) }, { h: 'Message', f: r => esc(r.text) }], p.samples) : '') +
      (p.noNumber.length ? `<h4 class="sect">No usable mobile number (${p.noNumber.length}): these members will not get it</h4>` + table([
        { h: 'No', f: r => esc(r.memberNo) }, { h: 'Member', f: r => esc(r.name) }, { h: 'Phone on record', f: r => esc(r.phoneOnRecord || '—') }], p.noNumber) : '');
    return p;
  };
  $('#msgPreview').onclick = () => preview().catch(x => toast(x.message, true));
  form.addEventListener('submit', async e => {
    e.preventDefault();
    try {
      const p = await preview();
      if (!p.withNumber) { toast('None of these members has a mobile number that can receive an SMS', true); return; }
      const ask = cfg.live
        ? `Send this SMS to ${p.withNumber} members? It uses about ${p.totalSms} SMS of credit and cannot be called back.`
        : `Test mode: prepare this message for ${p.withNumber} members? Nothing is sent.`;
      if (!confirm(ask)) return;
      await api('/api/messages', { method: 'POST', body: body() });
      toast(cfg.live ? 'Sending… the result of each member shows in the history' : 'Saved in test mode (not sent)');
      form.reset(); chosen.clear(); audienceSel.onchange(); $('#msgOut').innerHTML = '';
      $('#msgCount').textContent = '0 characters · 1 SMS per member';
      await loadHistory();
    } catch (x) { toast(x.message, true); }
  });
};

views.audit = async el => {
  const branches = isMgr() ? await api('/api/branches') : [];
  el.innerHTML = `<div class="panel"><form class="toolbar" id="auditFilter">` +
    (isMgr() ? sel('branchId', 'Branch', branches.map(b => [b.id, `${b.code} – ${b.name}`]), { blank: 'All branches' }) : '') +
    `<button class="btn">Apply</button></form>` +
    `<p class="muted" style="margin-top:0">Every edit and delete is recorded here with who did it and what the record looked like before.</p><div id="auditOut"></div></div>`;
  const load = async () => {
    const f = fd($('#auditFilter'));
    const rows = await api('/api/audit' + (f.branchId ? `?branchId=${f.branchId}` : ''));
    $('#auditOut').innerHTML = table([
      { h: 'When', f: r => esc(String(r.when).replace('T', ' ').slice(0, 16)) },
      ...(isMgr() ? [{ h: 'Branch', f: r => esc(r.branchCode || '') }] : []),
      { h: 'User', f: r => esc(r.user) },
      { h: 'Action', f: r => `<span class="badge ${r.action === 'DELETE' ? 'out' : 'in'}">${esc(label(r.action))}</span>` },
      { h: 'Record', f: r => `${esc(r.entityType)} #${r.entityId}` },
      { h: 'Details', f: r => esc(r.details || '') }
    ], rows);
  };
  $('#auditFilter').addEventListener('submit', e => { e.preventDefault(); load().catch(x => toast(x.message, true)); });
  await load();
};


/** Shows the monthly installment, total interest and total payable as the amount, rate and term are typed. */
/* ------------------------------------------------------------------ loan charges, taken out of what is paid out */
const FEES = { applicationFee: 0, processPercent: 0, insurancePercent: 0, legalCharges: 0, dailyInterest: false, defaultRate: 12 };
const loadFees = async () => { try { Object.assign(FEES, await api('/api/loans/fee-defaults')); } catch (x) { /* the form simply starts with nothing */ } };
const pctOf = (principal, pct) => Math.round(Number(principal || 0) * Number(pct || 0) / 100);     // whole shillings
const chargesOf = l => Number(l.applicationFee || 0) + Number(l.processFee || 0) + Number(l.insuranceFee || 0) + Number(l.legalCharges || 0);

/** The four charge boxes. {@code v}: a saved loan to start from; without one they start with the usual amounts. */
function loanFeeFields(v) {
  const start = (saved, usual) => (v && saved != null) ? saved : usual;
  const money2 = { type: 'number', step: '0.01', min: '0' };
  return `<h4 class="sect">Charges taken out of the payout</h4>` +
    `<div class="row2">${inp('processFee', `Loan process fee (${FEES.processPercent}% of the loan)`, { ...money2, value: start(v && v.processFee, 0) })}` +
    `${inp('insuranceFee', `Insurance (${FEES.insurancePercent}% of the loan)`, { ...money2, value: start(v && v.insuranceFee, 0) })}</div>` +
    `<div class="row2">${inp('applicationFee', 'Loan application fee', { ...money2, value: start(v && v.applicationFee, FEES.applicationFee) })}` +
    `${inp('legalCharges', 'Legal charges', { ...money2, value: start(v && v.legalCharges, FEES.legalCharges) })}</div>` +
    `<div id="feeInfo" class="jtotals"></div>`;
}

/**
 * Keeps the process fee and the insurance at their percentage of the loan while the amount is typed, until someone types over them,
 * and shows the charges together with what is paid out. {@code owed}: what a top-up clears on the earlier loan.
 */
function wireLoanFees(form, owed) {
  const box = n => $(`[name=${n}]`, form);
  const num = n => Number(box(n).value || 0);
  const auto = { processFee: () => pctOf(num('principal'), FEES.processPercent), insuranceFee: () => pctOf(num('principal'), FEES.insurancePercent) };
  const last = { processFee: auto.processFee(), insuranceFee: auto.insuranceFee() };
  const info = $('#feeInfo', form);
  const show = () => {
    const charges = num('processFee') + num('insuranceFee') + num('applicationFee') + num('legalCharges');
    const p = num('principal'), out = p - charges - (owed || 0);
    info.innerHTML = p > 0
      ? `<span>Charges <strong>${money(charges)}</strong></span>` + (owed ? `<span>Clears the old loan <strong>${money(owed)}</strong></span>` : '') +
        (out > 0 ? `<span>Paid out <strong>${money(out)}</strong></span>` : `<span style="color:#b00020"><strong>nothing left to pay out: the loan must be bigger than the charges${owed ? ' and the old balance' : ''}</strong></span>`)
      : '';
  };
  box('principal').addEventListener('input', () => {
    for (const n of ['processFee', 'insuranceFee']) {
      const now = auto[n]();
      if (num(n) === last[n]) box(n).value = now;      // still the automatic amount, so it follows the loan
      last[n] = now;
    }
    show();
  });
  ['processFee', 'insuranceFee', 'applicationFee', 'legalCharges'].forEach(n => box(n).addEventListener('input', show));
  show();
}
const feeBody = f => ({ applicationFee: Number(f.applicationFee || 0), processFee: Number(f.processFee || 0), insuranceFee: Number(f.insuranceFee || 0), legalCharges: Number(f.legalCharges || 0) });

function loanPreview(form, method) {
  const box = $('#loanPreview', form);
  let timer = null;
  const refresh = () => {
    const p = Number($('[name=principal]', form).value), r = $('[name=annualRatePercent]', form).value, n = Number($('[name=termMonths]', form).value);
    if (!(p > 0) || r === '' || !(n >= 1)) { box.innerHTML = '<span class="muted">Enter the amount, rate and term to see the monthly installment.</span>'; return; }
    clearTimeout(timer);
    timer = setTimeout(async () => {
      try {
        const q = new URLSearchParams({ principal: p, annualRatePercent: r, termMonths: n });
        if (method) q.set('method', method);
        const v = await api('/api/loans/preview?' + q);
        box.innerHTML = `<span>Monthly installment <strong>${money(v.installment)}</strong></span><span>Total interest <strong>${money(v.totalInterest)}</strong></span>` +
          `<span>Total payable <strong>${money(v.totalDue)}</strong></span>`;
      } catch (e) { box.innerHTML = `<span class="muted">${esc(e.message)}</span>`; }
    }, 250);
  };
  form.addEventListener('input', refresh);
  refresh();
}

const actions = {
  schedule(d) { return viewers.schedule(d.id); },
  addJournal() { return journalModal(null); },
  view(d) { return viewers[d.kind](d.id); },
  edit(d) { return editors[d.kind](d.id); },
  del(d) { confirmDelete(d.kind, d.id); },
  addBranch() {
    modal({
      title: 'Add branch', submit: 'Create branch',
      body: inp('code', 'Sacco ID (e.g. SAC001)', { req: 1 }) + inp('name', 'Branch name', { req: 1 }) + inp('location', 'Location'),
      onSubmit: async f => { await api('/api/branches', { method: 'POST', body: fd(f) }); toast('Branch created'); route(); }
    });
  },
  async toggleBranch(d) {
    try { await api(`/api/branches/${d.id}/active/${d.value}`, { method: 'PUT' }); route(); } catch (e) { toast(e.message, true); }
  },
  async addAdmin() {
    const mgr = isMgr();
    const branches = mgr ? await api('/api/branches') : [];
    if (mgr && !branches.length) { toast('Create a branch first', true); return; }
    const roles = mgr ? ['BRANCH_ADMIN', 'TREASURER', 'CASHIER'] : ['TREASURER', 'CASHIER'];
    modal({
      title: 'Add user', submit: 'Create user',
      body: (mgr ? sel('branchId', 'Branch', branches.map(b => [b.id, `${b.code} – ${b.name}`]), { req: 1 }) : '') +
        sel('role', 'Role', roles.map(r => [r, label(r)]), { req: 1 }) +
        inp('fullName', 'Full name', { req: 1 }) + inp('username', 'Username', { req: 1 }) +
        inp('password', 'Password (min 8 characters)', { type: 'password', req: 1 }),
      onSubmit: async f => {
        const v = fd(f);
        await api('/api/users', { method: 'POST', body: { ...v, branchId: mgr ? Number(v.branchId) : null } });
        toast('User created'); route();
      }
    });
  },
  async momoAdd() {
    const branches = await api('/api/branches');
    if (!branches.length) { toast('Create a branch first', true); return; }
    momoForm(null, branches);
  },
  momoEdit(d) {
    const n = MOMO.find(x => String(x.id) === d.id);
    if (n) momoForm(n);
  },
  async momoToggle(d) {
    const n = MOMO.find(x => String(x.id) === d.id);
    if (!n) return;
    try {
      await api(`/api/mobile-money/${n.id}`, { method: 'PUT', body: { branchId: n.branchId, network: n.network, number: n.number, holderName: n.holderName, active: !n.active } });
      route();
    } catch (e) { toast(e.message, true); }
  },
  momoRemove(d) {
    const n = MOMO.find(x => String(x.id) === d.id);
    if (!n) return;
    modal({
      title: `Remove ${n.network} ${n.number}?`, submit: 'Remove number',
      body: '<p class="muted">The number and its account are removed. If money has already moved through it, it cannot be removed; switch it off instead.</p>',
      onSubmit: async () => { await api(`/api/mobile-money/${n.id}`, { method: 'DELETE' }); toast('Number removed'); route(); }
    });
  },
  resetPassword(d) {
    modal({
      title: `Reset password · ${d.name}`, submit: 'Save password',
      body: inp('password', 'New password (min 8 characters)', { type: 'password', req: 1 }),
      onSubmit: async f => {
        await api(`/api/users/${d.id}/password`, { method: 'PUT', body: { password: fd(f).password } });
        toast('Password changed'); route();
      }
    });
  },
  async toggleUser(d) {
    try { await api(`/api/users/${d.id}/enabled/${d.value}`, { method: 'PUT' }); route(); } catch (e) { toast(e.message, true); }
  },
  addMember() {
    modal({
      title: 'Add member', submit: 'Add member',
      body: `<div class="row2">${inp('memberNo', 'Member no.', { req: 1 })}${inp('fullName', 'Full name', { req: 1 })}</div>` +
        `<div class="row2">${inp('phone', 'Phone')}${inp('nationalId', 'National ID')}</div>` +
        `<div class="row2">${inp('employer', 'Employer')}${inp('payrollNo', 'Payroll no.')}</div>`,
      onSubmit: async f => {
        const m = await api('/api/members', { method: 'POST', body: fd(f) });
        toast(`Member added. Login: username and password are "${m.loginUsername}"` + (m.loginUsername === m.fullName ? '' : ' (the password is the full names)'));
        route();
      }
    });
  },
  resetLogin(d) {
    modal({
      title: `Reset login · ${d.name}`, submit: 'Reset login',
      body: `<p class="muted">The member's password goes back to their full names, and the login is switched on.</p>`,
      onSubmit: async () => {
        const m = await api(`/api/members/${d.id}/reset-login`, { method: 'POST' });
        toast(`Login reset. Username: ${m.loginUsername}`); route();
      }
    });
  },
  async addLoan() {
    const members = await api('/api/members');
    if (!members.length) { toast('Add a member first', true); return; }
    await loadFees();
    modal({
      title: 'New loan application', submit: 'Save application',
      body: sel('memberId', 'Member', memberOptions(members), { req: 1 }) +
        `<div class="row2">${inp('loanDate', 'Loan date', { type: 'date', req: 1, value: today() })}${inp('termMonths', 'Term (months)', { type: 'number', min: '1', max: '600', req: 1, value: '12' })}</div>` +
        `<div class="row2">${inp('principal', 'Principal amount', { type: 'number', step: '0.01', min: '0.01', req: 1 })}${inp('annualRatePercent', 'Interest % per year (reducing balance)', { type: 'number', step: '0.01', min: '0', req: 1, value: String(FEES.defaultRate) })}</div>` +
        loanFeeFields() +
        `<div id="loanPreview" class="jtotals"></div>`,
      onMount: form => { loanPreview(form); wireLoanFees(form, 0); },
      onSubmit: async f => {
        const v = fd(f);
        await api('/api/loans', { method: 'POST', body: { memberId: Number(v.memberId), principal: Number(v.principal), ...feeBody(v), loanDate: v.loanDate || null, annualRatePercent: Number(v.annualRatePercent), termMonths: Number(v.termMonths) } });
        toast('Application saved. Disburse it when approved.'); route();
      }
    });
  },
  async topUp(d) {
    const old = await api(`/api/loans/${d.id}`);
    const owed = Number(old.outstanding);
    await loadFees();
    modal({
      title: `Top up loan #${old.id} · ${old.memberName}`, submit: 'Save top-up application',
      body: `<div class="kv"><div><span>Still owed on loan #${old.id} (principal + interest charged)</span><strong>${money(owed)}</strong></div></div>` +
        `<p class="muted">The new loan pays this off first. The member receives the new amount less this balance and less the charges.</p>` +
        `<div class="row2">${inp('loanDate', 'Loan date', { type: 'date', req: 1, value: today() })}${inp('termMonths', 'Term (months)', { type: 'number', min: '1', max: '600', req: 1, value: '12' })}</div>` +
        `<div class="row2">${inp('principal', 'New loan amount', { type: 'number', step: '0.01', min: '0.01', req: 1 })}${inp('annualRatePercent', 'Interest % per year (reducing balance)', { type: 'number', step: '0.01', min: '0', req: 1, value: String(old.annualRatePercent) })}</div>` +
        loanFeeFields() +
        `<div id="loanPreview" class="jtotals"></div>`,
      onMount: form => { loanPreview(form); wireLoanFees(form, owed); },
      onSubmit: async f => {
        const v = fd(f);
        const charges = Number(v.processFee || 0) + Number(v.insuranceFee || 0) + Number(v.applicationFee || 0) + Number(v.legalCharges || 0);
        if (Number(v.principal) - charges <= owed) throw new Error('The new loan, less the charges, must be bigger than what the member still owes.');
        await api('/api/loans', { method: 'POST', body: { memberId: old.memberId, principal: Number(v.principal), ...feeBody(v),
          loanDate: v.loanDate || null, annualRatePercent: Number(v.annualRatePercent), termMonths: Number(v.termMonths), topUpOfLoanId: old.id } });
        toast('Top-up application saved. Disburse it when approved.'); route();
      }
    });
  },
  async disburse(d) {
    const l = await api(`/api/loans/${d.id}`);
    const old = l.topUpOfLoanId ? await api(`/api/loans/${l.topUpOfLoanId}`) : null;
    const accts = await api('/api/accounts');
    const net = Number(l.principal) - chargesOf(l) - (old ? Number(old.outstanding) : 0);
    modal({
      title: `Disburse loan #${d.id}`, submit: 'Disburse',
      body: `<div class="kv"><div><span>Loan amount</span>${money(l.principal)}</div><div><span>Loan process fee</span>${money(l.processFee || 0)}</div>` +
        `<div><span>Insurance</span>${money(l.insuranceFee || 0)}</div><div><span>Loan application fee</span>${money(l.applicationFee)}</div>` +
        `<div><span>Legal charges</span>${money(l.legalCharges || 0)}</div>` +
        (old ? `<div><span>Clears loan #${old.id} (principal + interest)</span>${money(old.outstanding)}</div>` : '') +
        `<div><span>Paid out (to the bank or cash)</span><strong>${money(net)}</strong></div></div>` +
        `<div class="row2">${sel('receivingAccountId', 'Paid out from account', assetOptions(accts), { req: 1 })}${inp('date', 'Date', { type: 'date', value: today() })}</div>${inp('reference', 'Reference')}`,
      onMount: form => {
        const bank = accts.find(a => a.code === SOURCE_CODE.BANK);          // the bank account is the usual one to pay a loan from
        if (bank) $('select[name=receivingAccountId]', form).value = bank.id;
      },
      onSubmit: async f => {
        const v = fd(f);
        await api(`/api/loans/${d.id}/disburse`, { method: 'POST', body: { receivingAccountId: Number(v.receivingAccountId), reference: v.reference, date: v.date || null } });
        toast('Loan disbursed'); route();
      }
    });
  },
  reject(d) {
    modal({
      title: `Reject loan #${d.id}?`, submit: 'Reject loan',
      body: '<p class="muted">The application will be marked rejected. No money moves.</p>',
      onSubmit: async () => { await api(`/api/loans/${d.id}/reject`, { method: 'POST' }); toast('Loan rejected'); route(); }
    });
  },
  repay(d) {
    modal({
      title: `Repayment for loan #${d.id}`, submit: 'Record repayment',
      body: `<p class="muted">Outstanding: <strong>${money(d.out)}</strong></p>` +
        inp('amount', 'Amount', { type: 'number', step: '0.01', min: '0.01', max: d.out, req: 1 }) +
        `<div class="row2">${sel('source', 'Paid via', SOURCES)}${inp('date', 'Date', { type: 'date', value: today() })}</div>${inp('reference', 'Reference / receipt no.')}`,
      onSubmit: async f => {
        const v = fd(f);
        await api(`/api/loans/${d.id}/repayments`, { method: 'POST', body: { amount: Number(v.amount), source: v.source, reference: v.reference, date: v.date || null } });
        toast('Repayment recorded'); route();
      }
    });
  },
  async addAccount() {
    const all = await api('/api/accounts');
    modal({
      title: 'Add account', submit: 'Add account',
      body: sel('type', 'Account type', ACCOUNT_TYPES.map(t => [t, `${label(t)} (${TYPE_HINT[t]})`]), { req: 1 }) +
        `<div class="row2">${inp('code', 'Account code', { req: 1 })}${inp('name', 'Account name', { req: 1 })}</div>` +
        sel('parentId', 'Sub-account of (optional)', [], { blank: 'None (top level)' }) +
        inp('description', 'Description'),
      onMount: form => {
        const typeSel = $('select[name=type]', form), code = $('input[name=code]', form), parent = $('select[name=parentId]', form);
        let touched = false;
        code.addEventListener('input', () => { touched = true; });
        const refresh = () => {
          const t = typeSel.value;
          parent.innerHTML = '<option value="">None (top level)</option>' + parentOptions(all, t).map(([v, x]) => `<option value="${v}">${esc(x)}</option>`).join('');
          if (!touched) code.value = nextCode(all, t);
        };
        typeSel.addEventListener('change', refresh);
        refresh();
      },
      onSubmit: async f => {
        const v = fd(f);
        await api('/api/accounts', { method: 'POST', body: { code: v.code, name: v.name, type: v.type, parentId: v.parentId ? Number(v.parentId) : null, description: v.description } });
        toast('Account added'); route();
      }
    });
  },
  async editAccount(d) {
    const all = await api('/api/accounts');
    const a = all.find(x => String(x.id) === d.id);
    if (!a) { toast('Account not found', true); return; }
    modal({
      title: `Edit ${a.code}`, submit: 'Save changes',
      body: `<p class="muted">${esc(label(a.type))} account. The code and type cannot be changed.</p>` +
        inp('name', 'Account name', { req: 1, value: a.name }) +
        sel('parentId', 'Sub-account of (optional)', parentOptions(all, a.type, a.id), { blank: 'None (top level)' }) +
        inp('description', 'Description', { value: a.description || '' }),
      onMount: form => { if (a.parentId) $('select[name=parentId]', form).value = a.parentId; },
      onSubmit: async f => {
        const v = fd(f);
        await api(`/api/accounts/${a.id}`, { method: 'PUT', body: { name: v.name, parentId: v.parentId ? Number(v.parentId) : null, description: v.description } });
        toast('Account updated'); route();
      }
    });
  },
  async toggleAccount(d) {
    try { await api(`/api/accounts/${d.id}/active/${d.value}`, { method: 'PUT' }); route(); } catch (e) { toast(e.message, true); }
  },
  addProduct() { return productModal(null); },
  async editProduct(d) {
    const prods = await api('/api/products');
    const p = prods.find(x => String(x.id) === d.id);
    if (!p) { toast('Product not found', true); return; }
    return productModal(p);
  },
  async addPayroll() {
    const [members, activeLoans, accts] = await Promise.all([api('/api/members'), api('/api/loans?status=ACTIVE'), api('/api/accounts')]);
    const receiving = accts.filter(a => a.type === 'ASSET' && a.active);
    if (!receiving.length) { toast('Add an asset account (cash, bank or mobile money) to the chart of accounts first', true); return; }
    if (!members.length) { toast('Add members first', true); return; }
    const employers = [...new Set(members.map(m => m.employer).filter(Boolean))];
    modal({
      title: 'Post payroll deductions', submit: 'Post batch', wide: true,
      body: `<div class="row2"><label>Employer<input name="employer" list="employers" required><datalist id="employers">${employers.map(e => `<option value="${esc(e)}">`).join('')}</datalist></label>` +
        inp('period', 'Month', { type: 'month', req: 1, value: today().slice(0, 7) }) + `</div>` +
        `<div class="row2">${sel('receivingAccountId', 'Receiving account (where the money was received)', receiving.map(a => [a.id, `${a.code} – ${a.name}`]), { req: 1 })}` +
        `${inp('date', 'Transaction date', { type: 'date', req: 1, value: today() })}</div>` +
        `<div class="lines" id="lines"></div><button type="button" class="btn small" id="addLine">+ Add member line</button>` +
        `<p class="muted">Each line splits one member's deduction into savings, shares and loan repayment. The whole batch posts together or not at all.</p>`,
      onMount: form => {
        const bank = receiving.find(a => a.code === '1200');
        if (bank) $('select[name=receivingAccountId]', form).value = bank.id;
        const lines = $('#lines', form);
        const add = () => lines.insertAdjacentHTML('beforeend', lineHtml(members));
        add();
        $('#addLine', form).onclick = add;
        form.addEventListener('click', e => { if (e.target.matches('[data-rm]') && lines.children.length > 1) e.target.closest('.line').remove(); });
        form.addEventListener('change', e => {
          if (!e.target.matches('select[name=member]')) return;
          const loanSel = $('select[name=loan]', e.target.closest('.line'));
          const mine = activeLoans.filter(l => String(l.memberId) === e.target.value);
          loanSel.innerHTML = '<option value="">—</option>' + mine.map(l => `<option value="${l.id}">#${l.id} · to clear ${money(l.remaining)}</option>`).join('');
          if (mine.length) loanSel.value = mine[0].id;
        });
      },
      onSubmit: async f => {
        const v = fd(f);
        const lines = [...f.querySelectorAll('.line')].map(l => {
          const g = n => l.querySelector(`[name=${n}]`).value;
          return { memberId: Number(g('member')), savings: Number(g('savings') || 0), shares: Number(g('shares') || 0),
            loanRepayment: Number(g('repay') || 0), loanId: g('loan') ? Number(g('loan')) : null };
        });
        await api('/api/payroll', { method: 'POST', body: { employer: v.employer, period: v.period, receivingAccountId: Number(v.receivingAccountId), date: v.date, lines } });
        toast('Payroll batch posted'); route();
      }
    });
  }
};

$('#view').addEventListener('click', e => {
  const b = e.target.closest('[data-act]');
  if (!b || !actions[b.dataset.act]) return;
  Promise.resolve(actions[b.dataset.act](b.dataset)).catch(x => toast(x.message, true));
});

/* ------------------------------------------------------------------ boot */
(async function boot() {
  if (!S.auth) { showLogin(); return; }
  try { await loadConfig(); S.me = await api('/api/me', { login: true }); start(); }
  catch (e) { S.auth = null; showLogin(); }
})();
