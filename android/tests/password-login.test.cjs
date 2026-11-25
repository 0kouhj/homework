const assert = require("node:assert/strict");
const {readFileSync} = require("node:fs");
const {join} = require("node:path");
const {test} = require("node:test");
const vm = require("node:vm");

const source = readFileSync(join(__dirname, "../app/src/main/assets/password-login.js"), "utf8");
const homeOrigin = "https://www.icourse163.org";
const loginOrigin = "https://reg.icourse163.org";

function page({email, origin, pathname, parent}) {
    const intervals = new Map();
    const messages = [];
    let deliver;
    let clicks = 0;
    let intervalId = 0;

    class Input {
        get value() { return this.currentValue || ""; }
        set value(value) { this.currentValue = value; }
        getClientRects() { return [1]; }
        focus() {}
        blur() {}
        dispatchEvent() {}
    }

    const user = new Input();
    const password = new Input();
    const submit = {getClientRects: () => [1], click: () => { clicks++; }};
    const window = {
        parent,
        agendaAuth: {
            addEventListener: (type, listener) => { deliver = listener; },
            postMessage: message => messages.push(JSON.parse(message).event)
        },
        addEventListener: (type, listener) => { window.receive = listener; }
    };
    window.top = parent || window;
    const document = {
        readyState: "complete",
        cookie: "",
        querySelector: selector => selector.startsWith("input") ? user : submit,
        querySelectorAll: selector => selector === 'input[type="password"]' ? [password] : []
    };

    vm.runInNewContext("(() => {\n" + source + "\n})()", {
        agendaEmail: email,
        window,
        document,
        location: new URL(pathname, origin),
        HTMLInputElement: Input,
        Event,
        getComputedStyle: () => ({visibility: "visible"}),
        setInterval: callback => {
            intervals.set(++intervalId, callback);
            return intervalId;
        },
        clearInterval: id => intervals.delete(id)
    });

    return {
        window,
        messages,
        tick: () => [...intervals.values()].forEach(callback => callback()),
        provideCredentials: () => deliver({data: JSON.stringify({
            username: "fixture-user",
            password: "fixture-password"
        })}),
        clicks: () => clicks
    };
}

function setup(email) {
    const home = page({email, origin: homeOrigin, pathname: "/home.htm"});
    const form = page({email, origin: loginOrigin, pathname: "/webzj/v1.0.1/pub/index_dl2_new.html",
        parent: home.window});
    const frame = {
        postMessage(data, target) {
            assert.equal(target, loginOrigin);
            form.window.receive({data, origin: homeOrigin, source: home.window});
        }
    };
    const announce = (type, origin = loginOrigin) => home.window.receive?.({
        data: JSON.stringify({type}),
        origin,
        source: frame
    });
    return {home, form, announce};
}

test("phone form requests credentials only after the provider is ready and submits once", () => {
    const {form, announce} = setup(false);
    form.tick();
    assert.deepEqual(form.messages, []);

    announce("mbInitSuccess");
    form.tick();
    form.provideCredentials();
    form.provideCredentials();
    assert.deepEqual(form.messages, ["ready", "submitted"]);
    assert.equal(form.clicks(), 1);
});

test("email form ignores readiness from the wrong origin or login mode", () => {
    const {form, announce} = setup(true);
    announce("mbInitSuccess");
    announce("loginInitSuccess", "https://untrusted.example");
    form.tick();
    assert.deepEqual(form.messages, []);

    announce("loginInitSuccess");
    form.tick();
    form.provideCredentials();
    assert.equal(form.clicks(), 1);
});
