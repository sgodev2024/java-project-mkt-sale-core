import type { SVGProps } from "react";

const ICON_PATHS: Record<string, string[]> = {
  activity: ["M3 12h4l2.5-7 5 14 2.5-7H21"],
  apps: ["M4 4h6v6H4z", "M14 4h6v6h-6z", "M4 14h6v6H4z", "M14 14h6v6h-6z"],
  bell: ["M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9", "M10 21h4"],
  building: ["M4 21V4h10v17", "M14 9h6v12", "M8 8h2", "M8 12h2", "M8 16h2", "M17 13h1", "M17 17h1", "M2 21h20"],
  "check-circle": ["M22 11.1V12a10 10 0 1 1-5.9-9.1", "m9 11 3 3L22 4"],
  "chevron-down": ["m6 9 6 6 6-6"],
  "chevron-right": ["m9 18 6-6-6-6"],
  clock: ["M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20", "M12 6v6l4 2"],
  "clipboard-check": ["M9 5h6", "M9 3h6v4H9z", "M7 5H5v16h14V5h-2", "m8 14 2 2 4-5"],
  cpu: ["M9 9h6v6H9z", "M4 9h2", "M4 15h2", "M18 9h2", "M18 15h2", "M9 4v2", "M15 4v2", "M9 18v2", "M15 18v2", "M7 7h10v10H7z"],
  database: ["M4 6c0 2 3.6 3 8 3s8-1 8-3-3.6-3-8-3-8 1-8 3", "M4 6v6c0 2 3.6 3 8 3s8-1 8-3V6", "M4 12v6c0 2 3.6 3 8 3s8-1 8-3v-6"],
  download: ["M12 3v12", "m7 10 5 5 5-5", "M5 21h14"],
  file: ["M6 2h8l4 4v16H6z", "M14 2v6h6", "M9 13h6", "M9 17h6"],
  files: ["M6 2h8l4 4v14H6z", "M14 2v5h5", "M4 6H3v16h12v-2"],
  folder: ["M3 5h7l2 2h9v12H3z"],
  help: ["M9.1 9a3 3 0 1 1 5.3 1.9c-1.4 1.2-2.4 1.7-2.4 3.1", "M12 18h.01", "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20"],
  home: ["m3 11 9-8 9 8", "M5 10v11h14V10", "M9 21v-7h6v7"],
  layers: ["m12 2 9 5-9 5-9-5 9-5", "m3 12 9 5 9-5", "m3 17 9 5 9-5"],
  leaf: ["M20 4c-8 0-14 4-14 10 0 3 2 6 6 6 6 0 8-8 8-16Z", "M4 21c3-6 8-10 14-13"],
  lock: ["M6 10h12v11H6z", "M8 10V7a4 4 0 0 1 8 0v3", "M12 14v3"],
  logout: ["M10 17l5-5-5-5", "M15 12H3", "M14 3h7v18h-7"],
  menu: ["M4 7h16", "M4 12h16", "M4 17h16"],
  modules: ["M4 4h6v6H4z", "M14 4h6v6h-6z", "M4 14h6v6H4z", "M14 14h6v6h-6z"],
  plus: ["M12 5v14", "M5 12h14"],
  refresh: ["M20 6v5h-5", "M4 18v-5h5", "M6.1 9a7 7 0 0 1 11.6-2.6L20 11", "M4 13l2.3 4.6A7 7 0 0 0 18 15"],
  search: ["M11 19a8 8 0 1 1 0-16 8 8 0 0 1 0 16", "m21 21-4.3-4.3"],
  server: ["M4 4h16v6H4z", "M4 14h16v6H4z", "M8 7h.01", "M8 17h.01"],
  settings: ["M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7", "M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1-2.8 2.8-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21h-4v-.1a1.7 1.7 0 0 0-1-1.5 1.7 1.7 0 0 0-1.9.3l-.1.1L4.2 17l.1-.1a1.7 1.7 0 0 0 .3-1.9A1.7 1.7 0 0 0 3 14H3v-4h.1a1.7 1.7 0 0 0 1.5-1 1.7 1.7 0 0 0-.3-1.9L4.2 7 7 4.2l.1.1A1.7 1.7 0 0 0 9 4.6a1.7 1.7 0 0 0 1-1.5V3h4v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1L19.8 7l-.1.1a1.7 1.7 0 0 0-.3 1.9 1.7 1.7 0 0 0 1.5 1h.1v4h-.1a1.7 1.7 0 0 0-1.5 1Z"],
  shield: ["M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10", "m9 12 2 2 4-4"],
  sliders: ["M4 7h10", "M18 7h2", "M4 17h2", "M10 17h10", "M14 4v6", "M6 14v6"],
  star: ["m12 2 3.1 6.3 6.9 1-5 4.9 1.2 6.8-6.2-3.2L5.8 21 7 14.2 2 9.3l6.9-1L12 2Z"],
  upload: ["M12 21V9", "m7 14 5-5 5 5", "M5 3h14"],
  users: ["M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2", "M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8", "M22 21v-2a4 4 0 0 0-3-3.9", "M16 3.1a4 4 0 0 1 0 7.8"],
  webhook: ["M18 16.5a4 4 0 1 1-5.5-5.5", "M6 7.5A4 4 0 1 1 11.5 13", "M15 4a4 4 0 1 1-1 7.8", "M8.5 16.5h9", "m12 13 2.5 3.5"],
  x: ["M6 6l12 12", "M18 6 6 18"],
  zap: ["M13 2 3 14h9l-1 8 10-14h-9z"],
};

const LEGACY_ALIASES: Record<string, string> = {
  "⌂": "home", "▦": "apps", "⚙": "settings", "◫": "modules", "◇": "database",
  "◎": "users", "⌘": "building", "⊙": "shield", "↯": "activity", "▱": "files", "✓": "clipboard-check",
};

export function AppIcon({ name, size = 18, ...props }: { name: string; size?: number } & Omit<SVGProps<SVGSVGElement>, "name">) {
  const resolved = LEGACY_ALIASES[name] ?? name;
  const paths = ICON_PATHS[resolved] ?? ICON_PATHS.modules;
  return <svg aria-hidden="true" fill="none" height={size} viewBox="0 0 24 24" width={size} stroke="currentColor" strokeLinecap="round" strokeLinejoin="round" strokeWidth="1.8" {...props}>
    {paths.map((path, index) => <path d={path} key={`${resolved}-${index}`} />)}
  </svg>;
}
