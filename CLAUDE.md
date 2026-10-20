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
* The login page shows no branch list. The support login is reached by adding `#support` to the address.
* Removed for CPA: the dashboard "Branch summary" table, the Sacco ID on report headings (`FinancialReportService.branchLine`, `LoanScheduleService`), the Balance column on the General ledger screen.
* Added for CPA: "Change password" for every non-member login (`PUT /api/me/password`).

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
