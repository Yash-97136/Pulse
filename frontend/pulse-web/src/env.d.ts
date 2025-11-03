/// <reference types="node" />

declare namespace NodeJS {
  interface ProcessEnv {
    TRENDS_API_URL?: string;
    VITE_ENV_LABEL?: string;
  }
}
