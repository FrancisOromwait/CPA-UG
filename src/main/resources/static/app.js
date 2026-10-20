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
  $('#loginSub').textContent = b ? `Sign in to ${b.name}` : v === HQ ? 'Head office sign-in for the manager.' : 'Choose your branch, then sign in.';
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

async function loadLoginBranches() {
  const s = $('#loginBranch');
  try {
    await loadConfig();
    loginBranches = await api('/api/public/branches', { login: true });
    const support = location.hash === '#support';        // the support login is reached by adding #support to the address
    if (S.single && !support) {
      // one branch: nothing to choose, so the list is not shown
      s.innerHTML = loginBranches.map(b => `<option value="${b.id}">${esc(b.name)}</option>`).join('');
      s.closest('label').hidden = true;
    } else {
      s.closest('label').hidden = false;
      s.innerHTML = '<option value="">Select your branch…</option>' +
        `<option value="${HQ}">Head office (Manager)</option>` +
        loginBranches.map(b => `<option value="${b.id}">${esc(b.name)}</option>`).join('');
    }
    let last = ''; try { last = localStorage.getItem('lastBranch') || ''; } catch (e) { /* ignore */ }
    if (last && [...s.options].some(o => o.value === last)) s.value = last;
  } catch (err) {
    s.innerHTML = '<option value="">Could not load branches</option>';
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
    // The account must belong to the branch chosen on the form
    const chosen = f.get('branch');
    if (chosen === HQ) {
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
  ['reports/balance-sheet', 'Balance sheet'], ['reports/member-statement', 'Member statement']];
const NAV_ADMIN = [
  ['dashboard', 'Dashboard'],
  ['f-members', 'Members', [['members', 'Member list']]],
  ['f-deposits', 'Deposits', [['deposits', 'Regular deposit'], ['withdrawals', 'Savings withdrawal'], ['savingsinterest', 'Savings interest'], ['payroll', 'Payroll deductions'], ['payrollpaste', 'Payroll from Excel']]],
  ['f-loans', 'Loans', [['loans', 'Loan applications'], ['interest', 'Interest computation']]],
  ['f-accounting', 'Accounting', [['accounts', 'Chart of accounts'], ['products', 'Products'], ['journals', 'Journal entries'], ['gl', 'General ledger'], ['audit', 'Audit trail']]],
  ['f-reports', 'Reports', REPORT_KIDS]
];
/* a branch admin also gets the staff screen; treasurers and cashiers have the plain branch menu */
const navFor = () => isMember() ? [['mystatement', 'My statement']]
  : isMgr() ? NAV_MANAGER
  : isBranchAdmin() ? [...NAV_ADMIN.slice(0, 4), ['f-staff', 'Administration', [['staff', 'Staff users'], ...(S.single ? [['momo', 'Mobile money numbers']] : [])]], ...NAV_ADMIN.slice(4)]
  : NAV_ADMIN;
const NAV_MANAGER = [
  ['dashboard', 'Dashboard'],
  ['f-admin', 'Administration', [['branches', 'Branches'], ['momo', 'Mobile money numbers'], ['yearcodes', 'New year codes'], ['admins', 'Users']]],
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
ICONS.withdrawals = '<rect x="3" y="7" width="18" height="12" rx="2"/><path d="M12 16V4m0 0l-3.5 3.5M12 4l3.5 3.5"/><circle cx="12" cy="13" r="1.6"/>';
ICONS.mystatement = ICONS['reports/member-statement'];
ICONS.savingsinterest = ICONS.interest;
const navIcon = k => ICONS[k] ? `<span class="nav-ic"><svg viewBox="0 0 24 24" aria-hidden="true">${ICONS[k]}</svg></span>` : '';

function navHtml(items) {
  return items.map(([k, t, kids]) => kids
    ? `<div class="nav-group"><div class="nav-heading">${esc(t)}</div>` +
      `<div class="nav-sub">${kids.map(([ck, ct]) => `<a href="#${ck}" data-k="${ck}">${navIcon(ck)}<span>${esc(ct)}</span></a>`).join('')}</div></div>`
    : `<a href="#${k}" data-k="${k}">${navIcon(k)}<span>${esc(t)}</span></a>`).join('');
}

function start() {
  $('#login').hidden = true;
  $('#app').hidden = false;
  $('#nav').innerHTML = navHtml(navFor());
  $('#who').innerHTML = `${esc(S.me.fullName)}<small>${esc(label(S.me.role))}</small>`;
  const brand = isMgr() ? S.appName : S.me.branchName;
  $('#brandName').textContent = brand;
  $('#brandMark').innerHTML = markHtml(brand, isMgr() ? HQ : S.me.branchCode, true);
  document.title = isMgr() ? `${S.appName} · Support` : brand;
  const scopeText = isMgr() ? 'All branches' : S.single ? '' : `Sacco ID ${S.me.branchCode}`;
  $('#scope').textContent = scopeText;
  $('#scope').hidden = !scopeText;
  $('#chpw').hidden = isMember();
  route();
}

/* ------------------------------------------------------------------ routing */
const views = {};
const TITLES = { yearcodes: 'New year codes', momo: 'Mobile money numbers', withdrawals: 'Savings withdrawal', mystatement: 'My statement', dashboard: 'Dashboard', branches: 'Branches', admins: 'Users', staff: 'Staff users', members: 'Members',
  statement: 'Member statement', loans: 'Loans', payroll: 'Payroll deductions', payrollpaste: 'Payroll from Excel', accounts: 'Chart of accounts', products: 'Product definitions', gl: 'General ledger', reports: 'Reports', deposits: 'Regular deposit', audit: 'Audit trail', journals: 'Journal entries', interest: 'Interest computation', savingsinterest: 'Savings interest' };

async function route() {
  if (!S.me) return;
  const home = isMember() ? 'mystatement' : 'dashboard';
  const [name, arg] = (location.hash.slice(1) || home).split('/');
  const pageKey = name === 'reports' ? 'reports/' + (arg || 'trial-balance') : name;
  const allowed = new Set(navFor().flatMap(([k, , kids]) => kids ? kids.map(c => c[0]) : [k]));
  if (!isMember()) allowed.add('statement');
  if (!allowed.has(pageKey)) {          // e.g. a manager-only page left in the address from an earlier sign-in
    if (location.hash !== '#' + home) location.hash = '#' + home; else views[home]($('#view'));
    return;
  }
  const view = views[name] || views[home];
  const key = views[name] ? name : home;
  const navKey = key === 'statement' ? 'members' : key === 'reports' ? 'reports/' + (arg || 'trial-balance') : key;
  document.querySelectorAll('#nav a').forEach(a => a.classList.toggle('active', a.dataset.k === navKey));
  $('#title').textContent = TITLES[key];
  const el = $('#view');
  el.innerHTML = '<div class="empty">Loading…</div>';
  try { await view(el, arg); }
  catch (err) { el.innerHTML = `<div class="error">${esc(err.message)}</div>`; }
}
window.addEventListener('hashchange', route);

/* ------------------------------------------------------------------ dashboard */
const stat = (cls, l, v) => `<div class="stat ${cls}"><div class="l">${esc(l)}</div><div class="v">${v}</div></div>`;

views.dashboard = async el => {
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
  el.innerHTML =
    `<div class="cards">${stat('', 'Savings', money(t.savings))}${stat('b2', 'Shares', money(t.shares))}` +
    `${stat('b3', 'Loans outstanding', money(t.loanOutstanding))}${stat('b4', 'Payroll collected', money(t.payrollCollected))}` +
    `${stat('b5', 'Members', t.members)}${stat('', 'Active loans', t.activeLoans)}</div>` +
    // with one branch the table only repeats the cards above, so a one-branch installation does not show it
    (S.single && !isMgr() ? '' : `<div class="panel"><h3>${isMgr() ? 'Every branch' : 'Branch summary'}</h3>${table(cols, rep.branches, foot)}</div>`);
};

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
    `<div class="panel"><h3>${esc(m.fullName)} <span class="muted">· ${esc(m.memberNo)}${S.single ? '' : ' · ' + esc(m.branchCode)}</span></h3>` +
    `<div class="kv"><div><span>Phone</span>${esc(m.phone || '—')}</div><div><span>Employer</span>${esc(m.employer || '—')}</div><div><span>Payroll no.</span>${esc(m.payrollNo || '—')}</div></div></div>` +
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
  const run = async () => { $('#myOut').innerHTML = renderDoc(await api('/api/my/statement?' + query())); };
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
  'member-statement': { title: 'Member statement', range: true }
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
    return `<div class="panel"><h3>${esc(s.heading)}</h3><div class="tbl-wrap"><table${grid ? ' class="grid"' : ''}><thead><tr>${head}</tr></thead><tbody>${body}</tbody>${foot}</table></div></div>`;
  }).join('');
  return `<div class="panel report-head"><div class="muted">${esc(S.appName)}</div>` +
    `<h3 class="rep-title">${esc(doc.title)}</h3><div class="rep-branch">${esc(doc.branchLine)}</div>` +
    (doc.subjectLine ? `<div>${esc(doc.subjectLine)}</div>` : '') + `<div class="muted">${esc(doc.periodLine)}</div>` +
    `<div class="rep-totals">${doc.totals.map(t => `<div class="rep-total"><span>${esc(t.label)}</span><strong>${esc(t.value)}</strong></div>`).join('')}</div>` +
    (doc.note ? `<p class="muted"><em>${esc(doc.note)}</em></p>` : '') + `</div>` + sections;
}

async function reportPdf(kind, qs) { return pdfUrl(`/api/reports/${kind}/pdf?${qs}`); }

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
  const isStatement = kind === 'member-statement';
  $('#title').textContent = 'Reports · ' + R.title;
  const branches = isMgr() ? await api('/api/branches') : [];
  el.innerHTML = `<div class="panel"><form class="toolbar" id="repForm">` +
    (isMgr() ? sel('branchId', 'Branch', branches.map(b => [b.id, `${b.code} – ${b.name}`]),
      isStatement ? { req: 1, blank: 'Select a branch…' } : { blank: 'All branches (consolidated)' }) : '') +
    (isStatement ? sel('memberId', 'Member', [], { req: 1, blank: 'Select a member…' }) : '') +
    (R.range ? inp('from', 'From', { type: 'date', value: kind === 'income-expenditure' ? `${new Date().getFullYear()}-01-01` : '' }) : '') +
    inp('to', R.range ? 'To' : 'As at', { type: 'date', value: today() }) +
    `<button class="btn primary">View</button><button type="button" class="btn" id="repOpen">Open PDF</button>` +
    `<button type="button" class="btn" id="repDownload">Download PDF</button></form></div><div id="repOut"></div>`;

  const form = $('#repForm');
  const query = () => {
    const f = fd(form), qs = new URLSearchParams();
    ['branchId', 'memberId', 'from', 'to'].forEach(k => { if (f[k]) qs.set(k, f[k]); });
    return qs;
  };
  const run = async () => { $('#repOut').innerHTML = renderDoc(await api(`/api/reports/${kind}?` + query())); };

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
