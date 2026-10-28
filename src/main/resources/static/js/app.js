(function () {
    "use strict";

    const app = document.getElementById("app");
    const notifications = document.getElementById("notifications");
    const pageTitle = document.getElementById("page-title");
    const sidebar = document.getElementById("sidebar");
    const menuToggle = document.getElementById("menu-toggle");
    const MAX_FILE_SIZE = 10 * 1024 * 1024;
    const state = { selectedFile: null, basicAuth: null, csrfToken: null, csrfHeaderName: "X-XSRF-TOKEN" };
    let renderToken = 0;

    async function refreshCsrfToken() {
        const response = await fetch("/api/csrf", {
            credentials: "same-origin",
            cache: "no-store",
            headers: state.basicAuth ? { Authorization: `Basic ${state.basicAuth}` } : {}
        });
        let body = null;
        try { body = await response.json(); } catch (_) { /* empty response */ }
        if (!response.ok || !body || !body.token) {
            throw new Error(body && body.message ? body.message : `Request failed (${response.status}).`);
        }
        state.csrfToken = body.token;
        state.csrfHeaderName = body.headerName || "X-XSRF-TOKEN";
        return body;
    }

    const api = {
        async request(url, options) {
            const requestOptions = {
                ...(options || {}),
                credentials: "same-origin",
                headers: new Headers(options && options.headers)
            };
            if (state.basicAuth && url.startsWith("/api/")) {
                requestOptions.headers.set("Authorization", `Basic ${state.basicAuth}`);
            }
            if (state.csrfToken && requestOptions.method && !["GET", "HEAD", "OPTIONS"].includes(requestOptions.method.toUpperCase())) {
                requestOptions.headers.set(state.csrfHeaderName, state.csrfToken);
            }
            const response = await fetch(url, requestOptions);
            let body = null;
            try { body = await response.json(); } catch (_) { /* empty response */ }
            const method = (requestOptions.method || "GET").toUpperCase();
            if (response.status === 403 && url !== "/api/csrf"
                    && !["GET", "HEAD", "OPTIONS"].includes(method)
                    && !(options && options._csrfRetried)) {
                await refreshCsrfToken();
                return this.request(url, { ...options, _csrfRetried: true });
            }
            if (!response.ok) throw new Error(body && body.message ? body.message : `Request failed (${response.status}).`);
            return body;
        },
        get(url) { return this.request(url); },
        async download(url) {
            const headers = new Headers();
            if (state.basicAuth && url.startsWith("/api/")) {
                headers.set("Authorization", `Basic ${state.basicAuth}`);
            }
            const response = await fetch(url, { credentials: "same-origin", headers });
            if (!response.ok) {
                let body = null;
                try { body = await response.json(); } catch (_) { /* binary or empty response */ }
                throw new Error(body && body.message ? body.message : `Request failed (${response.status}).`);
            }
            const blob = await response.blob();
            const disposition = response.headers.get("Content-Disposition") || "";
            const match = disposition.match(/filename="([^"]+)"/i);
            const fileName = match ? match[1] : "reconciliation-export.xlsx";
            const objectUrl = URL.createObjectURL(blob);
            const link = document.createElement("a");
            link.href = objectUrl;
            link.download = fileName.replace(/[\\/:*?"<>|]/g, "_");
            document.body.appendChild(link);
            link.click();
            link.remove();
            URL.revokeObjectURL(objectUrl);
        }
    };

    function arrayResponse(body, resourceName) {
        if (!Array.isArray(body)) {
            throw new Error(`Unexpected ${resourceName} response from the server.`);
        }
        return body;
    }

    function escapeText(value) {
        return value === null || value === undefined || value === "" ? "—" : String(value);
    }
    function money(value) {
        if (value === null || value === undefined || value === "") return "—";
        return new Intl.NumberFormat(undefined, { style: "currency", currency: "EUR" }).format(Number(value));
    }
    function date(value) {
        if (!value) return "—";
        const parsed = new Date(value);
        return Number.isNaN(parsed.getTime()) ? escapeText(value) : parsed.toLocaleString();
    }
    function badge(value) {
        const text = escapeText(value).replaceAll("_", " ");
        const kind = /RECONCILED/.test(value) ? "success" : /MISMATCH|ERROR/.test(value) ? "danger" : /NOT_FOUND/.test(value) ? "warning" : "neutral";
        const el = document.createElement("span");
        el.className = `badge badge-${kind}`;
        el.textContent = text;
        return el;
    }
    function cell(row, value, className) {
        const td = document.createElement("td");
        if (className) td.className = className;
        td.textContent = escapeText(value);
        row.appendChild(td);
    }
    function table(headers, rows, emptyMessage, renderRow) {
        if (!rows || rows.length === 0) {
            const empty = document.createElement("div");
            empty.className = "empty";
            empty.textContent = emptyMessage || "No records found.";
            return empty;
        }
        const wrap = document.createElement("div");
        wrap.className = "table-wrap";
        const tableEl = document.createElement("table");
        const head = tableEl.createTHead().insertRow();
        headers.forEach(header => { const th = document.createElement("th"); th.textContent = header; head.appendChild(th); });
        const body = tableEl.createTBody();
        rows.forEach(item => renderRow(item, body.insertRow()));
        wrap.appendChild(tableEl);
        return wrap;
    }
    function setLoading(button, loading, label) {
        if (!button) return;
        button.disabled = loading;
        button.textContent = loading ? "Loading…" : label;
    }
    function notify(message, type) {
        notifications.replaceChildren();
        const notice = document.createElement("div");
        notice.className = `notice notice-${type || "error"}`;
        notice.textContent = message;
        notifications.appendChild(notice);
        window.setTimeout(() => notice.remove(), 6000);
    }
    function errorView(error) {
        const notice = document.createElement("div");
        notice.className = "notice notice-error";
        notice.textContent = error.message || "Unable to load data.";
        return notice;
    }
    function heading(title, description) {
        const div = document.createElement("div");
        div.className = "page-heading";
        const h = document.createElement("div");
        const h1 = document.createElement("h2"); h1.textContent = title;
        const p = document.createElement("p"); p.className = "muted"; p.textContent = description || "";
        h.append(h1, p); div.appendChild(h); return div;
    }
    function stat(label, value) {
        const div = document.createElement("div"); div.className = "stat";
        const span = document.createElement("span"); span.textContent = label;
        const strong = document.createElement("strong"); strong.textContent = escapeText(value);
        div.append(span, strong); return div;
    }
    function navigate(hash) { window.location.hash = hash; }

    function loginView() {
        app.replaceChildren(heading("Administrator sign in", "Sign in to access orders, dashboard data, uploads, and reconciliation history."));
        const card = document.createElement("form");
        card.className = "card login-card";
        const username = field("Username", "login-username", "Administrator username");
        const password = field("Password", "login-password", "Administrator password");
        password.querySelector("input").type = "password";
        const message = document.createElement("p");
        message.className = "notice notice-error";
        message.hidden = true;
        const submit = button("Sign in", "primary", async () => {
            const value = `${username.querySelector("input").value}:${password.querySelector("input").value}`;
            state.basicAuth = btoa(unescape(encodeURIComponent(value)));
            setLoading(submit, true, "Sign in");
            try {
                await api.get("/api/orders");
                await refreshCsrfToken();
                window.location.hash = window.location.hash || "#dashboard";
                route();
            } catch (_) {
                state.basicAuth = null;
                message.textContent = "Invalid administrator credentials.";
                message.hidden = false;
                setLoading(submit, false, "Sign in");
            }
        });
        card.addEventListener("submit", event => { event.preventDefault(); submit.click(); });
        card.append(username, password, message, submit);
        app.appendChild(card);
        username.querySelector("input").focus();
    }

    async function dashboard(token) {
        app.replaceChildren(heading("Dashboard", "Current order status and historical reconciliation activity."));
        const loading = document.createElement("div"); loading.className = "loading"; loading.textContent = "Loading dashboard…"; app.appendChild(loading);
        try {
            const [stats, history] = await Promise.all([
                api.get("/api/dashboard/stats"),
                api.get("/api/reconcile/history")
            ]);
            if (token !== renderToken) return;
            loading.remove();
            const statsEl = document.createElement("div"); statsEl.className = "stats";
            [["Total orders", stats.totalOrders], ["Pending", stats.pendingOrders], ["Reconciled", stats.reconciledOrders],
                ["Amount mismatches", stats.amountMismatchOrders], ["Reports processed", stats.totalBatches],
                ["Rows processed", stats.totalRowsProcessed]].forEach(item => statsEl.appendChild(stat(item[0], item[1])));
            app.appendChild(statsEl);
            const actions = document.createElement("div"); actions.className = "card actions";
            actions.append(button("Upload a report", "primary", () => navigate("#upload")), button("View orders", "secondary", () => navigate("#orders")));
            app.appendChild(actions);
            const activity = document.createElement("section"); activity.className = "card"; activity.style.marginTop = "16px";
            const title = document.createElement("h2"); title.textContent = "Historical activity";
            activity.appendChild(title);
            if (!history.length) {
                const p = document.createElement("p"); p.className = "muted"; p.textContent = "No reconciliation reports have been uploaded yet.";
                activity.appendChild(p);
            } else {
                const list = document.createElement("div"); list.className = "activity-list";
                history.slice(0, 4).forEach(batch => {
                    const item = document.createElement("div"); item.className = "activity-item";
                    const copy = document.createElement("div");
                    const name = document.createElement("div"); name.className = "activity-name"; name.textContent = batch.fileName;
                    const meta = document.createElement("div"); meta.className = "activity-meta";
                    meta.textContent = `${date(batch.uploadedAt)} · ${batch.totalRows} rows`;
                    copy.append(name, meta);
                    item.append(copy, badge(`${batch.successfulMatches} matches`));
                    list.appendChild(item);
                });
                activity.appendChild(list);
            }
            app.appendChild(activity);
        } catch (error) { loading.replaceWith(errorView(error)); }
    }
    function button(label, className, handler) {
        const el = document.createElement("button"); el.type = "button"; el.className = className; el.textContent = label; el.addEventListener("click", handler); return el;
    }

    async function orders() {
        const pageHeading = heading("Orders", "Search and filter the current order register.");
        pageHeading.appendChild(button("Add order", "primary", () => showOrderForm()));
        pageHeading.appendChild(button("Import orders", "secondary", () => showOrderImport()));
        app.replaceChildren(pageHeading);
        const results = document.createElement("div");
        const form = document.createElement("form"); form.className = "filters card";
        const searchField = field("Search", "order-search", "Tracking number or customer");
        const statusField = field("Status", "order-status", "");
        const select = document.createElement("select"); select.id = "order-status";
        [["", "All statuses"], ["PENDING", "Pending"], ["RECONCILED", "Reconciled"], ["AMOUNT_MISMATCH", "Amount mismatch"]]
            .forEach(item => { const option = document.createElement("option"); option.value = item[0]; option.textContent = item[1]; select.appendChild(option); });
        statusField.replaceChildren(statusField.querySelector("label"), select);
        const submit = button("Apply filters", "primary", () => loadOrders(searchField.querySelector("input").value, select.value, results));
        submit.type = "submit"; form.append(searchField, statusField, submit);
        form.addEventListener("submit", event => { event.preventDefault(); loadOrders(searchField.querySelector("input").value, select.value, results); });
        const clear = button("Clear", "secondary", () => { searchField.querySelector("input").value = ""; select.value = ""; loadOrders("", "", results); });
        form.appendChild(clear); app.append(form, results);
        await loadOrders("", "", results);
    }
    function field(labelText, id, placeholder) {
        const div = document.createElement("div"); div.className = "field";
        const label = document.createElement("label"); label.htmlFor = id; label.textContent = labelText;
        const input = document.createElement("input"); input.id = id; input.placeholder = placeholder || ""; input.type = "text";
        div.append(label, input); return div;
    }
    async function loadOrders(search, status, target) {
        const results = target || app.lastElementChild;
        results.replaceChildren(Object.assign(document.createElement("div"), { className: "loading", textContent: "Loading orders…" }));
        try {
            const query = new URLSearchParams(); if (search) query.set("search", search); if (status) query.set("status", status);
            const data = arrayResponse(await api.get(`/api/orders?${query}`), "orders");
            results.replaceChildren(table(["Tracking number", "Customer", "Expected amount", "Status", "Created", "Updated", "Actions"], data,
                "No orders match the selected filters.", (order, row) => {
                    cell(row, order.trackingNumber);
                    cell(row, order.customerName);
                    cell(row, money(order.expectedAmount), "money");
                    const status = document.createElement("td"); status.appendChild(badge(order.status)); row.appendChild(status);
                    cell(row, date(order.createdAt)); cell(row, date(order.updatedAt));
                    const actions = document.createElement("td");
                    actions.append(button("Details", "secondary", () => showOrder(order.id)),
                        button("Edit", "secondary", () => showOrderForm(order)));
                    row.appendChild(actions);
                }));
        } catch (error) { results.replaceChildren(errorView(error)); }
    }

    function orderInput(labelText, id, type, value, placeholder) {
        const wrapper = field(labelText, id, placeholder);
        const input = wrapper.querySelector("input");
        input.type = type;
        input.value = value || "";
        if (type === "number") {
            input.min = "0.01";
            input.step = "0.01";
            input.inputMode = "decimal";
        }
        return wrapper;
    }

    function showOrderForm(order) {
        const editing = Boolean(order);
        const title = editing ? "Edit order" : "Add order";
        app.replaceChildren(heading(title, editing
            ? "Update order details without changing recorded reconciliation history."
            : "Create a new order. New orders start in Pending status."));
        const form = document.createElement("form");
        form.className = "card order-form";
        const message = document.createElement("div");
        message.className = "notice notice-error";
        message.hidden = true;
        const tracking = orderInput("Tracking number", "order-tracking", "text",
            editing ? order.trackingNumber : "", "e.g. TRK-1006");
        const customer = orderInput("Customer name", "order-customer", "text",
            editing ? order.customerName : "", "Customer name");
        const amount = orderInput("Expected amount", "order-amount", "number",
            editing ? order.expectedAmount : "", "0.00");
        const actions = document.createElement("div");
        actions.className = "actions";
        const save = button(editing ? "Save changes" : "Create order", "primary", async () => {
            message.hidden = true;
            setLoading(save, true, editing ? "Save changes" : "Create order");
            try {
                const body = {
                    trackingNumber: tracking.querySelector("input").value,
                    customerName: customer.querySelector("input").value,
                    expectedAmount: amount.querySelector("input").value
                };
                await api.request(editing ? `/api/orders/${order.id}` : "/api/orders", {
                    method: editing ? "PUT" : "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(body)
                });
                notify(editing ? "Order updated successfully." : "Order created successfully.", "success");
                window.location.hash = "#orders";
            } catch (error) {
                message.textContent = error.message;
                message.hidden = false;
                setLoading(save, false, editing ? "Save changes" : "Create order");
            }

        });
        const cancel = button("Cancel", "secondary", () => navigate("#orders"));
        actions.append(save, cancel);
        form.addEventListener("submit", event => { event.preventDefault(); save.click(); });
        form.append(tracking, customer, amount, message, actions);
        app.appendChild(form);
        tracking.querySelector("input").focus();
    }

    function showOrderImport() {
        app.replaceChildren(heading("Import orders", "Preview an XLSX file before creating new Pending orders."));
        const card = document.createElement("section"); card.className = "card";
        const input = document.createElement("input"); input.type = "file"; input.accept = ".xlsx";
        const selected = document.createElement("p"); selected.className = "muted"; selected.textContent = "No file selected.";
        const message = document.createElement("div"); message.hidden = true; message.className = "notice notice-error";
        const results = document.createElement("div");
        const previewButton = button("Preview import", "primary", async () => {
            message.hidden = true; results.replaceChildren();
            const file = input.files && input.files[0];
            if (!file) { message.textContent = "Select an XLSX file first."; message.hidden = false; return; }
            if (!file.name.toLowerCase().endsWith(".xlsx") || file.size === 0 || file.size > MAX_FILE_SIZE) {
                message.textContent = "Select a non-empty XLSX file no larger than 10 MB.";
                message.hidden = false; return;
            }
            setLoading(previewButton, true, "Preview import");
            try {
                const form = new FormData(); form.append("file", file);
                const preview = await api.request("/api/orders/import/preview", { method: "POST", body: form });
                renderImportPreview(preview, results, confirmButton);
            } catch (error) { message.textContent = error.message; message.hidden = false; }
            finally { setLoading(previewButton, false, "Preview import"); }
        });
        const confirmButton = button("Confirm import", "primary", async () => {
            if (!confirmButton.dataset.importId) return;
            setLoading(confirmButton, true, "Confirm import");
            try {
                const response = await api.request(`/api/orders/import/confirm?importId=${encodeURIComponent(confirmButton.dataset.importId)}`,
                    { method: "POST" });
                notify(`${response.createdCount} order(s) imported successfully.`, "success");
                navigate("#orders");
            } catch (error) {
                message.textContent = error.message;
                message.hidden = false;
                setLoading(confirmButton, false, "Confirm import");
            }
        });
        confirmButton.disabled = true;
        input.addEventListener("change", () => {
            const file = input.files && input.files[0];
            selected.textContent = file ? `${file.name} (${Math.ceil(file.size / 1024)} KB)` : "No file selected.";
            confirmButton.disabled = true;
            delete confirmButton.dataset.importId;
            results.replaceChildren();
        });
        const cancel = button("Cancel", "secondary", () => navigate("#orders"));
        card.append(input, selected, previewButton, confirmButton, cancel, message, results);
        app.appendChild(card);
    }

    function renderImportPreview(preview, target, confirmButton) {
        confirmButton.dataset.importId = preview.importId;
        confirmButton.disabled = preview.validRows === 0;
        const summary = document.createElement("p");
        summary.className = "muted";
        summary.textContent = `Rows: ${preview.totalRows}; ready: ${preview.validRows}; errors: ${preview.errorRows}; `
            + `duplicates: ${preview.duplicateRows}; already in database: ${preview.existingRows}.`;
        const errors = preview.errors.map(error => ({
            rowNumber: error.rowNumber, trackingNumber: error.trackingNumber || "—", reason: error.reason
        }));
        target.replaceChildren(summary, table(["Excel row", "Tracking number", "Reason"], errors,
            "All non-empty rows are ready for import.", (error, row) => {
                cell(row, error.rowNumber);
                cell(row, error.trackingNumber);
                cell(row, error.reason);
            }));
    }

    async function showOrder(id) {
        app.replaceChildren(heading("Order details", "Order information and recorded reconciliation results."));
        const back = button("Back to orders", "secondary", () => navigate("#orders"));
        app.firstChild.appendChild(back);
        const invoiceButton = button("Export Invoice PDF", "primary", () => downloadPdf(
            `/api/orders/${id}/invoice/pdf`, invoiceButton, "Export Invoice PDF", "Invoice PDF downloaded."));
        app.firstChild.appendChild(invoiceButton);
        const target = document.createElement("div");
        target.className = "loading";
        target.textContent = "Loading order…";
        app.appendChild(target);
        try {
            const details = await api.get(`/api/orders/${id}`);
            const section = document.createElement("section");
            section.className = "card order-detail";
            const fields = [
                ["Tracking number", details.order.trackingNumber],
                ["Customer", details.order.customerName],
                ["Expected amount", money(details.order.expectedAmount)],
                ["Status", details.order.status],
                ["Created", date(details.order.createdAt)],
                ["Updated", date(details.order.updatedAt)]
            ];
            const grid = document.createElement("div");
            grid.className = "detail-grid";
            fields.forEach(([label, value]) => {
                const item = document.createElement("div");
                const name = document.createElement("span"); name.className = "muted"; name.textContent = label;
                const valueElement = document.createElement("strong");
                valueElement.textContent = escapeText(value);
                item.append(name, valueElement);
                grid.appendChild(item);
            });
            section.appendChild(grid);
            const historyTitle = document.createElement("h2");
            historyTitle.textContent = "Reconciliation history";
            section.appendChild(historyTitle);
            section.appendChild(table(["Batch row", "Collected amount", "Outcome", "Delivery date", "Explanation"],
                details.reconciliationHistory, "No reconciliation results are recorded for this order.", (item, row) => {
                    cell(row, item.rowNumber);
                    cell(row, money(item.collectedAmount), "money");
                    const outcome = document.createElement("td"); outcome.appendChild(badge(item.outcome)); row.appendChild(outcome);
                    cell(row, item.deliveryDate);
                    cell(row, item.message);
                }));
            target.replaceWith(section);
        } catch (error) { target.replaceWith(errorView(error)); }
    }

    function upload() {
        app.replaceChildren(heading("Upload & Reconcile", "Process an XLSX courier report against the order register."));
        const card = document.createElement("section"); card.className = "card";
        const zone = document.createElement("div"); zone.className = "drop-zone"; zone.tabIndex = 0;
        const icon = document.createElement("div"); icon.className = "upload-icon"; icon.setAttribute("aria-hidden", "true"); icon.textContent = "⇧";
        const text = document.createElement("p"); text.textContent = "Drop your courier report here";
        const hint = document.createElement("p"); hint.className = "drop-hint"; hint.textContent = "XLSX files only · maximum 10 MB";
        const input = document.createElement("input"); input.type = "file"; input.accept = ".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; input.id = "file-input"; input.hidden = true;
        const choose = button("Choose file", "secondary", () => input.click()); choose.type = "button";
        zone.append(icon, text, hint, choose, input); const fileName = document.createElement("p"); fileName.className = "file-name"; fileName.textContent = "No file selected.";
        const submit = button("Upload and reconcile", "primary", () => submitUpload(submit, fileName, results)); submit.type = "button"; submit.disabled = true;
        const results = document.createElement("div"); results.style.marginTop = "1rem";
        input.addEventListener("change", () => chooseFile(input.files[0], fileName, submit));
        ["dragenter", "dragover"].forEach(event => zone.addEventListener(event, e => { e.preventDefault(); zone.classList.add("dragover"); }));
        ["dragleave", "drop"].forEach(event => zone.addEventListener(event, e => { e.preventDefault(); zone.classList.remove("dragover"); }));
        zone.addEventListener("drop", e => chooseFile(e.dataTransfer.files[0], fileName, submit));
        zone.addEventListener("keydown", e => { if (e.key === "Enter" || e.key === " ") input.click(); });
        card.append(zone, fileName, submit, results); app.appendChild(card);
    }
    function chooseFile(file, label, submit) {
        state.selectedFile = null; submit.disabled = true;
        if (!file) { label.textContent = "No file selected."; return; }
        if (!file.name.toLowerCase().endsWith(".xlsx")) { label.textContent = "Only .xlsx files are supported."; notify("Only .xlsx files are supported.", "error"); return; }
        if (file.size > MAX_FILE_SIZE) { label.textContent = "File exceeds the 10 MB limit."; notify("The selected file exceeds the 10 MB limit.", "error"); return; }
        state.selectedFile = file; label.textContent = `${file.name} (${(file.size / 1024 / 1024).toFixed(2)} MB)`; submit.disabled = false;
    }
    async function submitUpload(submit, label, results) {
        if (!state.selectedFile) return;
        setLoading(submit, true, "Upload and reconcile");
        const form = new FormData(); form.append("file", state.selectedFile);
        try {
            const batch = await api.request("/api/reconcile/upload", { method: "POST", body: form });
            notify("Report uploaded and reconciled successfully.", "success");
            results.replaceChildren(renderBatch(batch));
            state.selectedFile = null; label.textContent = "No file selected.";
            submit.disabled = true; setLoading(submit, false, "Upload and reconcile");
        } catch (error) { notify(error.message, "error"); setLoading(submit, false, "Upload and reconcile"); }
    }
    function renderBatch(batch, problemsOnly) {
        const section = document.createElement("section"); const summary = document.createElement("div"); summary.className = "summary";
        const problemCount = batch.mismatches + batch.notFound + batch.errors;
        [["Rows", batch.totalRows], ["Matches", batch.successfulMatches], ["Mismatches", batch.mismatches], ["Unknown", batch.notFound], ["Errors", batch.errors], ["Problems", problemCount]]
            .forEach(item => summary.appendChild(stat(item[0], item[1])));
        section.appendChild(summary);
        const controls = document.createElement("div"); controls.className = "filters card";
        const filter = document.createElement("select"); filter.id = "batch-row-filter"; filter.setAttribute("aria-label", "Filter report rows");
        [["all", "All rows"], ["problems", `Problem rows (${problemCount})`]].forEach(([value, label]) => {
            const option = document.createElement("option"); option.value = value; option.textContent = label;
            option.selected = (problemsOnly ? "problems" : "all") === value; filter.appendChild(option);
        });
        controls.appendChild(filter); section.appendChild(controls);
        const rowTable = table(["Row", "Tracking number", "Expected", "Collected", "Delivery date", "Outcome", "Explanation"],
            filteredBatchItems(batch, problemsOnly), "No row results returned.", renderBatchRow);
        const results = document.createElement("div"); results.className = "batch-results"; results.appendChild(rowTable);
        section.appendChild(results);
        filter.addEventListener("change", () => {
            const onlyProblems = filter.value === "problems";
            results.replaceChildren(table(["Row", "Tracking number", "Expected", "Collected", "Delivery date", "Outcome", "Explanation"],
                filteredBatchItems(batch, onlyProblems), onlyProblems ? "No problem rows in this report." : "No row results returned.", renderBatchRow));
        });
        return section;
    }
    function filteredBatchItems(batch, problemsOnly) {
        return problemsOnly ? batch.items.filter(item => ["AMOUNT_MISMATCH", "NOT_FOUND", "ERROR"].includes(item.outcome)) : batch.items;
    }
    function renderBatchRow(item, row) {
        cell(row, item.rowNumber); cell(row, item.trackingNumber); cell(row, money(item.expectedAmount), "money");
        cell(row, money(item.collectedAmount), "money"); cell(row, item.deliveryDate);
        const outcome = document.createElement("td"); outcome.appendChild(badge(item.outcome)); row.appendChild(outcome);
        cell(row, item.message);
    }
    async function history() {
        app.replaceChildren(heading("Reconciliation History", "Search and analyze saved courier reports."));
        const form = document.createElement("form"); form.className = "filters card";
        const search = field("Filename", "history-search", "Part or full filename");
        const from = dateField("From date", "history-from"), to = dateField("To date", "history-to");
        const outcomeField = document.createElement("div"); outcomeField.className = "field";
        const outcome = document.createElement("select"); outcome.id = "history-outcome";
        const label = document.createElement("label"); label.htmlFor = outcome.id; label.textContent = "Outcome";
        [["", "All reports"], ["HAS_MATCHES", "Has successful matches"], ["HAS_MISMATCHES", "Has amount mismatches"],
            ["HAS_NOT_FOUND", "Has unknown tracking numbers"], ["HAS_ERRORS", "Has invalid rows"],
            ["PROBLEMS", "Has problems"], ["SUCCESSFUL_ONLY", "Successful only"]].forEach(([value, text]) => {
            const option = document.createElement("option"); option.value = value; option.textContent = text; outcome.appendChild(option);
        });
        outcomeField.append(label, outcome);
        const results = document.createElement("div");
        const load = async () => {
            const fromValue = from.querySelector("input").value, toValue = to.querySelector("input").value;
            if (fromValue && toValue && fromValue > toValue) {
                results.replaceChildren(errorView({ message: "From date must not be after To date." })); return;
            }
            results.replaceChildren(Object.assign(document.createElement("div"), { className: "loading", textContent: "Loading history..." }));
            try {
                const query = new URLSearchParams(), searchValue = search.querySelector("input").value;
                if (searchValue) query.set("search", searchValue);
                if (fromValue) query.set("from", fromValue); if (toValue) query.set("to", toValue);
                if (outcome.value) query.set("outcome", outcome.value);
                const data = arrayResponse(await api.get(`/api/reconcile/history?${query}`), "history");
                const count = document.createElement("p"); count.className = "muted"; count.textContent = `${data.length} report(s) found.`;
                results.replaceChildren(count, table(["Filename", "Uploaded", "Rows", "Matches", "Mismatches", "Unknown", "Errors", "Problems", "Action"],
                    data, "No reports match the selected filters.", (batch, row) => {
                        cell(row, batch.fileName); cell(row, date(batch.uploadedAt)); cell(row, batch.totalRows);
                        cell(row, batch.successfulMatches); cell(row, batch.mismatches); cell(row, batch.notFound); cell(row, batch.errors);
                        cell(row, batch.mismatches + batch.notFound + batch.errors);
                        const action = document.createElement("td"); action.appendChild(button("Open", "secondary", () => showBatch(batch.batchId))); row.appendChild(action);
                    }));
            } catch (error) { results.replaceChildren(errorView(error)); }
        };
        const apply = button("Apply filters", "primary", load); apply.type = "submit";
        const clear = button("Reset", "secondary", () => { form.reset(); load(); });
        form.append(search, from, to, outcomeField, apply, clear);
        form.addEventListener("submit", event => { event.preventDefault(); load(); });
        app.append(form, results); await load();
    }
    function dateField(labelText, id) {
        const wrapper = field(labelText, id, ""); wrapper.querySelector("input").type = "date"; return wrapper;
    }

    async function showBatch(id) {
        const exportButton = button("Export to Excel", "primary", () => downloadBatch(id, exportButton));
        const pdfButton = button("Export to PDF", "secondary", () => downloadPdf(
            `/api/reconcile/history/${id}/export/pdf`, pdfButton, "Export to PDF", "Reconciliation PDF downloaded."));
        app.replaceChildren(heading("Report details", "Persisted results for this reconciliation batch."));
        const back = button("Back to history", "secondary", history); app.firstChild.appendChild(back);
        app.firstChild.append(exportButton, pdfButton);
        const target = document.createElement("div"); target.className = "loading"; target.textContent = "Loading report…"; app.appendChild(target);
        try { target.replaceWith(renderBatch(await api.get(`/api/reconcile/history/${id}`))); }
        catch (error) { target.replaceWith(errorView(error)); }
    }
    async function downloadBatch(id, buttonElement) {
        setLoading(buttonElement, true, "Export to Excel");
        try {
            await api.download(`/api/reconcile/history/${id}/export`);
            notify("Reconciliation report downloaded.", "success");
        } catch (error) {
            notify(error.message, "error");
        } finally {
            setLoading(buttonElement, false, "Export to Excel");
        }
    }
    async function downloadPdf(url, buttonElement, label, successMessage) {
        setLoading(buttonElement, true, label);
        try {
            await api.download(url);
            notify(successMessage, "success");
        } catch (error) {
            notify(error.message, "error");
        } finally {
            setLoading(buttonElement, false, label);
        }
    }

    function route() {
        const token = ++renderToken;
        const page = (window.location.hash || "#dashboard").slice(1).split("/")[0];
        const titles = { dashboard: "Dashboard", orders: "Orders", upload: "Upload & Reconcile", history: "Reconciliation History" };
        pageTitle.textContent = titles[page] || titles.dashboard;
        document.querySelectorAll("[data-nav]").forEach(link => link.classList.toggle("active", link.dataset.nav === page));
        sidebar.classList.remove("open"); menuToggle.setAttribute("aria-expanded", "false");
        if (!state.basicAuth && page !== "health") {
            loginView();
            return;
        }
        document.getElementById("logout-button").hidden = !state.basicAuth;
        if (page === "orders") orders(); else if (page === "upload") upload(); else if (page === "history") history(); else dashboard(token);
    }
    document.getElementById("logout-button").addEventListener("click", () => {
        state.basicAuth = null;
        state.csrfToken = null;
        window.location.hash = "#dashboard";
        route();
    });
    menuToggle.addEventListener("click", () => {
        const open = sidebar.classList.toggle("open");
        menuToggle.setAttribute("aria-expanded", String(open));
    });
    window.addEventListener("hashchange", route);
    route();
}());
