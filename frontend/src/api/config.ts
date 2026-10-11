/** 与后端 server.servlet.context-path=/api 对齐。开发环境由 Vite 代理到 8123。 */
export const API_BASE = import.meta.env.VITE_API_BASE ?? '/api'
