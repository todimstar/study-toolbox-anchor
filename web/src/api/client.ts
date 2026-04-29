/** Axios-based API client. */

import axios, { AxiosError, InternalAxiosRequestConfig } from 'axios';
import type { APKFile, Device, InstallTask, PaginatedResponse } from '../types';

const client = axios.create({
  baseURL: '/api',
  timeout: 30000,
});

// ── Auth interceptor ──────────────────────────────────────────────

client.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = localStorage.getItem('token');
  if (token && config.headers) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// ── Response interceptor – redirect to login on 401 ──────────────

client.interceptors.response.use(
  (resp) => resp,
  (error: AxiosError) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  },
);

// ── Auth ──────────────────────────────────────────────────────────

export async function loginApi(username: string, password: string) {
  const resp = await client.post('/login', { username, password });
  return resp.data as { access_token: string; token_type: string; username: string };
}

export async function registerApi(username: string, password: string) {
  const resp = await client.post('/register', { username, password });
  return resp.data as { access_token: string; token_type: string; username: string };
}

// ── Devices ───────────────────────────────────────────────────────

export async function listDevices() {
  const resp = await client.get('/devices');
  return resp.data as PaginatedResponse<Device>;
}

// ── APKs ──────────────────────────────────────────────────────────

export async function uploadApk(file: File) {
  const form = new FormData();
  form.append('file', file);
  const resp = await client.post('/apps/upload', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  });
  return resp.data as { id: number; original_filename: string; file_size: number; message: string };
}

export async function listApks(page = 1, pageSize = 50) {
  const resp = await client.get('/apps', { params: { page, page_size: pageSize } });
  return resp.data as PaginatedResponse<APKFile>;
}

export async function deleteApk(apkId: number) {
  await client.delete(`/apps/${apkId}`);
}

// ── Tasks ─────────────────────────────────────────────────────────

export async function createTask(apkId: number, deviceId: number) {
  const resp = await client.post('/tasks', { apk_id: apkId, device_id: deviceId });
  return resp.data as InstallTask;
}

export async function listTasks(
  page = 1,
  pageSize = 50,
  filters?: { status?: string; device_id?: number },
) {
  const resp = await client.get('/tasks', { params: { page, page_size: pageSize, ...filters } });
  return resp.data as PaginatedResponse<InstallTask>;
}
