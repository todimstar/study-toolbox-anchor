/** API response types for the web panel. */

export interface User {
  username: string;
  access_token: string;
}

export interface Device {
  id: number;
  device_id: string;
  device_name: string;
  model: string;
  is_online: boolean;
  last_seen: string;
  created_at: string;
}

export interface APKFile {
  id: number;
  original_filename: string;
  file_size: number;
  package_name: string;
  version_name: string;
  uploaded_at: string;
}

export interface InstallTask {
  id: number;
  apk_id: number;
  apk_name: string;
  device_id: number;
  device_name: string;
  status: TaskStatus;
  retry_count: number;
  error_message: string;
  created_at: string;
  updated_at: string;
}

export type TaskStatus =
  | 'pending'
  | 'downloading'
  | 'downloaded'
  | 'installing'
  | 'success'
  | 'failed';

export interface PaginatedResponse<T> {
  total: number;
  items: T[];
}
