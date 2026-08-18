import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const documentPath = resolve(root, "docs", "technical-change-register.md");
const startMarker = "<!-- AUTO-GENERATED:START -->";
const endMarker = "<!-- AUTO-GENERATED:END -->";

function git(...args) {
  return execFileSync("git", args, { cwd: root, encoding: "utf8" }).trim();
}

function escapeCell(value) {
  return value.replaceAll("|", "\\|").replaceAll("\n", " ");
}

const raw = git("log", "--date=short", "--no-renames", "--pretty=format:@@COMMIT@@%n%H%x1f%ad%x1f%an%x1f%s", "--name-status");
const commits = [];
let current;
for (const line of raw.split(/\r?\n/)) {
  if (line === "@@COMMIT@@") {
    current = { files: [] };
    commits.push(current);
    continue;
  }
  if (!current) continue;
  if (!current.hash) {
    const [hash, date, author, ...subject] = line.split("\u001f");
    current.hash = hash;
    current.date = date;
    current.author = author;
    current.subject = subject.join("\u001f");
    continue;
  }
  if (/^[A-Z][0-9]*\t/.test(line)) current.files.push(line);
}

const relevant = commits.filter((commit) => commit.hash && commit.files.some((line) => {
  const path = line.split("\t").at(-1);
  return path !== "docs/technical-change-register.md";
}));
const latest = relevant[0];
const table = [
  "| Ngày | Commit | Nội dung | Tác giả | Số file |",
  "|---|---|---|---|---:|",
  ...relevant.map((commit) => `| ${commit.date} | [\`${commit.hash.slice(0, 7)}\`](https://github.com/sgodev2024/java-core/commit/${commit.hash}) | ${escapeCell(commit.subject)} | ${escapeCell(commit.author)} | ${commit.files.length} |`),
].join("\n");

const details = relevant.map((commit) => {
  const files = commit.files.map((line) => {
    const [status, ...paths] = line.split("\t");
    return `- \`${status}\` ${paths.map((path) => `\`${path}\``).join(" → ")}`;
  }).join("\n");
  return `### ${commit.date} — ${commit.subject}\n\n- Commit: [\`${commit.hash}\`](https://github.com/sgodev2024/java-core/commit/${commit.hash})\n- Tác giả: ${commit.author}\n- Phạm vi file:\n\n${files}`;
}).join("\n\n");

const generated = `${startMarker}\n> Sinh tự động từ Git. Mốc mã gần nhất: \`${latest?.hash ?? "N/A"}\` (${latest?.date ?? "N/A"}). Không sửa trực tiếp phần này.\n\n${table}\n\n## Chi tiết file theo commit\n\n${details}\n${endMarker}`;
const existing = readFileSync(documentPath, "utf8");
const start = existing.indexOf(startMarker);
const end = existing.indexOf(endMarker);
if (start < 0 || end < start) throw new Error("Không tìm thấy marker tự động trong technical change register");
const updated = `${existing.slice(0, start)}${generated}${existing.slice(end + endMarker.length)}`;
if (updated !== existing) writeFileSync(documentPath, updated, "utf8");
