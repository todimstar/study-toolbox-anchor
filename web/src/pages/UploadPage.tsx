/** APK upload page with drag-and-drop. */

import React, { useState } from 'react';
import {
  Button,
  Card,
  message,
  Select,
  Space,
  Upload,
} from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd';
import { createTask, listDevices, uploadApk } from '../api/client';
import type { Device } from '../types';

const { Dragger } = Upload;

const UploadPage: React.FC = () => {
  const [devices, setDevices] = useState<Device[]>([]);
  const [selectedDevice, setSelectedDevice] = useState<number | null>(null);
  const [uploading, setUploading] = useState(false);
  const [fileList, setFileList] = useState<UploadFile[]>([]);

  // Load devices on mount
  React.useEffect(() => {
    listDevices()
      .then((d) => setDevices(d.items))
      .catch(() => message.warning('无法加载设备列表'));
  }, []);

  const handleUpload = async () => {
    if (!fileList.length) {
      message.warning('请先选择 APK 文件');
      return;
    }
    if (!selectedDevice) {
      message.warning('请选择目标设备');
      return;
    }

    setUploading(true);
    try {
      const file = fileList[0].originFileObj as File;
      // Step 1: upload APK to server
      const apk = await uploadApk(file);
      // Step 2: create installation task on the selected device
      await createTask(apk.id, selectedDevice);

      message.success(`已下发安装任务: ${file.name}`);
      setFileList([]);
    } catch (err: any) {
      message.error(err?.response?.data?.detail || '下发失败');
    } finally {
      setUploading(false);
    }
  };

  return (
    <>
      <Card title="上传 APK 并下发安装" style={{ marginBottom: 24 }}>
        <Space direction="vertical" style={{ width: '100%' }} size="large">
          <Dragger
            maxCount={1}
            fileList={fileList}
            beforeUpload={(file) => {
              if (!file.name.endsWith('.apk')) {
                message.error('只能上传 .apk 文件');
                return Upload.LIST_IGNORE;
              }
              setFileList([{ uid: '-1', name: file.name, status: 'done', originFileObj: file as any }]);
              return false; // manual upload
            }}
            onRemove={() => setFileList([])}
          >
            <p className="ant-upload-drag-icon">
              <InboxOutlined />
            </p>
            <p className="ant-upload-text">点击或拖拽 APK 文件到此区域</p>
            <p className="ant-upload-hint">支持单个 APK 上传，文件将存储到服务器</p>
          </Dragger>

          <div>
            <span style={{ marginRight: 12 }}>目标设备：</span>
            <Select
              style={{ width: 300 }}
              placeholder="选择设备"
              value={selectedDevice}
              onChange={setSelectedDevice}
              options={devices.map((d) => ({
                label: `${d.device_name} (${d.model}) ${d.is_online ? '🟢在线' : '⚫离线'}`,
                value: d.id,
              }))}
            />
          </div>

          <Button
            type="primary"
            size="large"
            onClick={handleUpload}
            loading={uploading}
            disabled={!fileList.length || !selectedDevice}
          >
            下发安装任务
          </Button>
        </Space>
      </Card>
    </>
  );
};

export default UploadPage;
