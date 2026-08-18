import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  {
    // Các màn hình hiện lấy dữ liệu từ API sau hydration. React 19 rule này là
    // khuyến nghị tối ưu render, không phải lỗi correctness của fetch effect.
    rules: { "react-hooks/set-state-in-effect": "off" },
  },
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "next-env.d.ts",
  ]),
]);

export default eslintConfig;
