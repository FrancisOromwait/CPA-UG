# CPA Uganda

A one-branch sacco system (savings, shares, loans, payroll deductions, reports), separate from every other installation.

* **Address:** http://localhost:8082
* **Database:** `cpa_uganda` in MySQL (created on the first start)
* **Start:** double-click `start.bat`. **Stop:** `stop.bat`.

## First start
The first start creates the branch **CPA Uganda**, its administrator `admin`, and a support login `support`.
If no password is set, a random one is made for each and **written once in the start-up window**: write them down.
To choose them yourself, set `CPA_ADMIN_PASSWORD` and `MANAGER_PASSWORD` before the first start.
Everyone can change their own password with **Change password** (bottom left).

## Who does what
* **admin** (branch administrator): everything in the branch, including the chart of accounts, mobile money numbers and creating treasurers and cashiers.
* **treasurer / cashier**: the same as the administrator except managing users and the chart.
* **member**: only their own statement.
* **support**: for whoever looks after the system. Not shown on the login page: open `http://localhost:8082/#support`.
