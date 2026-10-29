# CPA Uganda: notes for whoever works on this project

A one-branch sacco system for the client **CPA Uganda**. It was made as a **copy of the multi-branch "Estates Sacco Manager"** (`D:\Estates Sacco Manager`), a different client's live system.

## Keep the two systems apart
* **Never edit `D:\Estates Sacco Manager`** from here. It is another client's running system.
* The code is copied, not shared: a fix made here does not reach the other system, and the other way round. Port a change by hand when asked.
* Separate databases: this one is `cpa_uganda`; the other is `sacco_db`. Never point this app at `sacco_db`.

## What is different here (single-branch mode)
* Settings in `src/main/resources/application.properties`: port **8082**, database **cpa_uganda**, `sacco.single-branch.*`, `sacco.app-name=CPA Uganda`. The class that reads them is `service/DeploymentSettings.java`.
* On first start `BootstrapRunner` creates the branch CPA Uganda (code CPA001), the administrator `admin`, and a support manager `support`. With no password set, random ones are written once to the start-up log.
* There is no manager in daily use. The branch administrator keeps the chart of accounts and the mobile money numbers (`SecurityConfig`, `MobileMoneyController`).
* The login page asks only for username and password, for every account including the support login: the role comes from the account itself (app.js `oneBranchLogin`).
* Dashboard for CPA (`DashboardService`, `GET /api/reports/dashboard`, `views.dashboard` in app.js): greeting and quick buttons, six figure cards (members, savings, shares, loans outstanding, loan interest owed, cash & bank; the member summary's figures), a 12-month money in / money out bar chart (blue / black, tooltip, figures table), cash and bank balances, latest money movements, and "needs attention" (pending loan applications). The old per-branch table shows only with several branches.
* Removed for CPA: the dashboard "Branch summary" table, the Sacco ID on report headings (`FinancialReportService.branchLine`, `LoanScheduleService`), the Balance column on the General ledger screen.
* Added for CPA: "Change password" for every non-member login (`PUT /api/me/password`).
* Security (after an audit scored 55%): `SecurityConfig` sends HSTS, a strict Content-Security-Policy (`CONTENT_SECURITY_POLICY`: nothing from other sites), Referrer-Policy, Permissions-Policy and Cross-Origin-Opener-Policy on every answer, refusals included (headers written eagerly). `LoginGuard`: 4 wrong passwords in a row lock a login (`app_users.failed_logins`, `locked`, `locked_at`; audited as LOCK); whoever manages a login unlocks it by resetting the password: the branch admin for treasurers, cashiers (Users → Reset password) and members (Reset login); the support login for anyone, including a locked administrator; cashiers and treasurers unlock no one. The support login's own lock ends after 10 minutes. A locked login is told the owner's wording (`SecurityConfig.LOCKED_MESSAGE`, answer marked `"locked":true`), shown in red on the login page (`.error.locked`, the one place red is used). `SUPPORT_RESET=true` with `MANAGER_PASSWORD` (Railway variables) sets the support password on the next start.
* Added for CPA: a Savings folder on the menu (savings withdrawal, savings interest).
* Added for CPA: Administration → Messages, SMS to members (`MessageService`, `/api/messages`, tables `sms_batches` and `sms_recipients`). Send to all, members with a loan, one employer's, or chosen members; `{name} {memberNo} {savings} {shares} {loan}` are filled in per member; the first Ugandan mobile number on the member's record is used. Provider from `SMS_PROVIDER` (default `test`: nothing is sent) with `SMS_USERNAME`, `SMS_API_KEY`, `SMS_SENDER_ID` as Railway variables; `africastalking` is built in (`AfricasTalkingSms`). Only the branch administrator sends.
* Added for CPA: member photos (`MemberPhotoService`, `/api/members/{id}/photo`, `/api/my/photo`, table `member_photos`). Kept in the database (Railway's files are lost on every deploy), shrunk to a JPEG of at most 400 px; any staff login adds, changes or removes one (audited); shown on the member's page and on the member statement, on screen and in the PDF (`PdfReportService.render(doc, by, photo)`).
* Added for CPA: Reports → Member summary (`/api/reports/member-summary`, `FinancialReportService.memberSummary`): every member on one line with the member statement's closing columns, worked out in grouped queries; `BranchIsolationTest` checks each line against the member's own statement. Reports → Savings / Shares / Loan report (`balanceReport`) list one kind of balance with the same figures.
* Added for CPA: a "Savings and credit menu" folder on the menu (member list, regular deposit, loan applications, savings withdrawal).
* Added for CPA: the menu starts Dashboard, "Accounts menu" (cashbook receipts, cashbook payments, journal entries, chart of accounts, then "Accounts reports", a drop-down holding the general ledger and every report; there is no separate Reports folder), "Savings and credit menu", "Administrative functions" (savings interest computation, loan interest computation, payroll deductions, payroll from Excel). Accounting keeps products and the audit trail. A folder entry with a third element (`['d-key', 'Label', [screens]]`, app.js `navHtml`) is a drop-down opened by a click (`openDrop`); it opens by itself when one of its screens is shown.
* Added for CPA: Cashbook receipts and payments (`/api/reports/cashbook-receipts`, `/api/reports/cashbook-payments`, `FinancialReportService.cashbook`): every debit (receipts) or credit (payments) on the cash, bank and mobile money accounts (`sacco.accounts.cashbook` plus mobile money), one table per account; a payroll batch is one line; money moved between own cash/bank accounts is marked "Transfer from/to" and totalled apart. The "+ New receipt" / "+ New payment" buttons save a two-line journal (`/api/journals`), so member-controlled accounts are refused.

## Technology
Spring Boot 3.3.5, Java 21, Maven, Spring Data JPA (Hibernate, ddl-auto=update), MySQL from XAMPP (`C:\xampp\mysql`), OpenPDF for PDFs, Lombok. The web app is one static page: `src/main/resources/static/app.js` (all screens) with `index.html` and `style.css`. Security is stateless HTTP Basic, with roles MANAGER (here: the support login), BRANCH_ADMIN, TREASURER, CASHIER, MEMBER.

## How the books work
Double entry. A transaction is saved in `ledger_entries`, then `PostingService.record` writes the member ledger (`member_ledger`) and the general ledger (`general_ledger`) in the same transaction. Reports are built once as a `ReportDoc` (`FinancialReportService`) and drawn both on screen (`renderDoc` in app.js) and as a PDF (`PdfReportService`). Chart of accounts, products, loan schedules, monthly interest runs, loan top-ups, savings withdrawals (with fee), mobile money numbers and member logins all exist as in the original.

## Run, stop, test
* Start: `start.bat` (starts MySQL if needed, then the app, then opens the browser). Stop: `stop.bat`.
* Stop the app before `mvn clean`, or Windows locks the build folder.
* Tests: `mvn -B clean test` (about 3 to 4 minutes). `BranchIsolationTest` covers the shared rules, `SingleBranchTest` covers this set-up.
* Back up before any database change: `C:\xampp\mysql\bin\mysqldump.exe -uroot --single-transaction cpa_uganda > backups\cpa_uganda_<what>.sql`.

## Working style the owner prefers
* Look at the code before changing it; run the tests; restart the app and say what was checked and what was not.
* Look and feel: black, blue and white only. Headings are plain labels. Icons on the menu.
* Explain simply. The owner is learning, so show where a thing lives (file and line) when asked.
* Windows: use PowerShell for commands. Passwords and first-run secrets are never written into files.
