import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { cp, mkdir, readFile } from "node:fs/promises";
import test from "node:test";
import { fileURLToPath } from "node:url";

const PORT = 3311;
const ROOT = fileURLToPath(new URL("..", import.meta.url));
const BASE_URL = `http://127.0.0.1:${PORT}`;

async function waitForServer(attempts = 60) {
  for (let i = 0; i < attempts; i++) {
    try {
      const response = await fetch(BASE_URL, { headers: { accept: "text/html" } });
      if (response.status < 500) return;
    } catch {
      // server chưa sẵn sàng, thử lại
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  throw new Error(`standalone server not ready at ${BASE_URL}`);
}

test("control plane console server-renders the login shell", async () => {
  await mkdir(new URL("../.next/standalone/.next", import.meta.url), { recursive: true });
  await cp(new URL("../.next/static", import.meta.url), new URL("../.next/standalone/.next/static", import.meta.url), { recursive: true });
  await cp(new URL("../public", import.meta.url), new URL("../.next/standalone/public", import.meta.url), { recursive: true });
  const server = spawn(process.execPath, [".next/standalone/server.js"], {
    cwd: ROOT,
    env: { ...process.env, PORT: String(PORT), HOSTNAME: "127.0.0.1" },
    stdio: "ignore",
  });
  try {
    await waitForServer();
    const response = await fetch(BASE_URL, { headers: { accept: "text/html" } });
    assert.equal(response.status, 200);
    assert.match(response.headers.get("content-type") ?? "", /^text\/html\b/i);

    const html = await response.text();
    assert.match(html, /<title>Core Platform — Control Plane<\/title>/i);
    assert.match(html, /<html lang="vi">/);
    assert.match(html, /Đang kiểm tra phiên đăng nhập/);
    assert.match(html, /auth-loading/);
    assert.doesNotMatch(html, /Your site is taking shape|react-loading-skeleton|codex-preview/i);
  } finally {
    server.kill();
  }
});

test("personal task navigation is not hard-coded in the application shell", async () => {
  const source = await readFile(new URL("../app/page.tsx", import.meta.url), "utf8");
  assert.doesNotMatch(source, /Công việc của tôi/i);
  assert.match(source, /api\/v1\/navigation\/me/);
});

test("home is a standalone top-level entry and business owns module navigation", async () => {
  const source = await readFile(new URL("../app/page.tsx", import.meta.url), "utf8");
  const styles = await readFile(new URL("../app/globals.css", import.meta.url), "utf8");
  assert.match(source, /if\(section\.key==="home"\).*renderPageButton\(home\)/s);
  assert.match(source, /className={`nav-section-trigger/);
  assert.match(source, /rootItems\.length===0.*Chưa có module được cấp quyền/s);
  assert.match(styles, /\.nav-section-trigger\s*\{/);
  assert.match(styles, /\.nav-section-children\s*\{/);
});

test("home page does not render the deployment environment summary strip", async () => {
  const source = await readFile(new URL("../app/page.tsx", import.meta.url), "utf8");
  const styles = await readFile(new URL("../app/globals.css", import.meta.url), "utf8");
  assert.doesNotMatch(source, /environment-panel|Mô hình vận hành|Dedicated deployment<\/small>/i);
  assert.doesNotMatch(styles, /environment-panel|environment-label/i);
});

test("approval demo is isolated from the production application shell", async () => {
  const shell = await readFile(new URL("../app/page.tsx", import.meta.url), "utf8");
  const demo = await readFile(new URL("../app/demo/approval-workspace.tsx", import.meta.url), "utf8");
  assert.doesNotMatch(shell, /api\/v1\/approvals/);
  assert.match(shell, /dynamic\(\(\) => import\("\.\/demo\/approval-workspace"\)/);
  assert.match(demo, /api\/v1\/approvals/);
});

test("ESG login copy, semantic icons and real operations APIs are enforced", async () => {
  const shell = await readFile(new URL("../app/page.tsx", import.meta.url), "utf8");
  const styles = await readFile(new URL("../app/globals.css", import.meta.url), "utf8");
  const packageJson = JSON.parse(await readFile(new URL("../package.json", import.meta.url), "utf8"));
  assert.equal(packageJson.dependencies.next, "16.3.1");
  assert.match(shell, /Giải pháp tối ưu hóa vận hành doanh nghiệp/);
  assert.match(shell, /Quản trị vận hành, tài nguyên, phân quyền từ một trung tâm duy nhất/);
  assert.match(styles, /\.auth-message h1\s*\{[^}]*color:\s*#fff/i);
  assert.match(styles, /--navy-950:\s*#092e28/i);
  assert.match(shell, /control-plane\/jobs/);
  assert.match(shell, /control-plane\/outbox/);
  assert.match(shell, /api\/v1\/auth\/refresh/);
  assert.doesNotMatch(shell, /<strong>12<\/strong>|<strong>0\.8s<\/strong>/);
});
