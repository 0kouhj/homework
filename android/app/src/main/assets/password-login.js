"use strict";
const bridge = window.agendaAuth;
if (!bridge) return;

const host = location.hostname;
const topFrame = window === window.top;
const moocHomeOrigin = "https://www.icourse163.org";
const moocLoginOrigin = "https://reg.icourse163.org";
const ursReadyEvent = agendaEmail ? "loginInitSuccess" : "mbInitSuccess";
const ursReadyMessage = "agenda-mooc-urs-initialized";
const visible = element => !!element?.getClientRects().length &&
    getComputedStyle(element).visibility !== "hidden";
const compact = value => String(value || "").replace(/\s+/g, "");
const send = event => bridge.postMessage(JSON.stringify({event, path: location.pathname}));

function exactText(doc, selector, name) {
    return [...doc.querySelectorAll(selector)].filter(item => visible(item) && name.test(compact(item.textContent))).pop();
}

function fill(input, value) {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set;
    input.focus();
    setter.call(input, value);
    input.dispatchEvent(new Event("input", {bubbles: true}));
    input.dispatchEvent(new Event("change", {bubbles: true}));
    input.blur();
}

const moocForm = host === "reg.icourse163.org" && !topFrame && /^(?:\/webzj\/v[0-9.]+)?\/pub\/index_dl2_new[.]html$/.test(location.pathname);
if (moocForm) {
    let initialized = false, requested = false, sent = false;
    window.addEventListener("message", event => {
        if (event.origin === moocHomeOrigin && event.source === window.parent && event.data === ursReadyMessage) {
            initialized = true;
        }
    });

    function fields() {
        const user = document.querySelector(agendaEmail ? 'input[name="email"][type="text"]' : "input#phoneipt");
        const pass = [...document.querySelectorAll('input[type="password"]')].find(visible);
        const submit = document.querySelector(agendaEmail ? "a#dologin" : "a#submitBtn");
        return [user, pass, submit];
    }

    bridge.addEventListener("message", message => {
        if (!requested || sent) return;
        const [user, pass, submit] = fields();
        if (![user, pass, submit].every(visible)) return;
        const credentials = JSON.parse(message.data);
        sent = true;
        fill(user, credentials.username);
        fill(pass, credentials.password);
        send("submitted");
        submit.click();
    });

    const timer = setInterval(() => {
        if (!requested && initialized && document.readyState !== "loading" && fields().every(visible)) {
            requested = true;
            clearInterval(timer);
            send("ready");
        }
    }, 200);
}

if (host === "www.icourse163.org" && topFrame && location.pathname === "/home.htm") {
    // URS notifies its parent after initialization. Relay that signal to the frame that sent it.
    window.addEventListener("message", event => {
        if (event.origin !== moocLoginOrigin || !event.source) return;
        let message = event.data;
        if (typeof message === "string") {
            try { message = JSON.parse(message); }
            catch (_) { return; }
        }
        if (message?.type === ursReadyEvent) event.source.postMessage(ursReadyMessage, moocLoginOrigin);
    }, true);

    let opened = false, selected = false;
    let checking = false, nextCheck = 0;
    function verifySession() {
        if (checking || Date.now() < nextCheck) return;
        const cookies = document.cookie.split(";").map(value => value.trim());
        const seed = cookies.find(value => value.startsWith("NTESSTUDYSI="));
        if (!seed || !cookies.some(value => value.startsWith("STUDY_INFO="))) return;
        checking = true;
        nextCheck = Date.now() + 2000;
        const endpoint = "/web/j/memberAccountBean.getPhoneBindPopupStatus.rpc?csrfKey="
            + encodeURIComponent(seed.slice("NTESSTUDYSI=".length));
        fetch(endpoint, {method: "POST", credentials: "same-origin", redirect: "error",
            body: new URLSearchParams()})
            .then(response => response.ok ? response.json() : null)
            .then(data => {
                if (data?.code === 0 && typeof data.result === "boolean") {
                    clearInterval(timer);
                    send("verified");
                }
            })
            .catch(() => {})
            .finally(() => { checking = false; });
    }
    const timer = setInterval(() => {
        verifySession();
        if (!opened) {
            const signIn = exactText(document, "a,button,span,div", /^登录\|注册$/);
            if (signIn) {
                opened = true;
                signIn.click();
            }
        }
        if (opened && !selected) {
            const mode = exactText(document, "a,button,span,div,label",
                agendaEmail ? /^邮箱登录$/ : /^手机号登录$/);
            if (mode) {
                selected = true;
                mode.click();
            }
        }
    }, 250);
}

