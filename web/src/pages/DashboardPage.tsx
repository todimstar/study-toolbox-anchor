/** Task Dashboard – list and monitor installation tasks. */

import React, { useCallback, useEffect, useState } from 'react';
import { Badge, Card, Select, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { listTasks } from '../api/client';
import type { InstallTask, TaskStatus } from '../types';

const { Text } = Typography;

const STATUS_MAP: Record<TaskStatus, { color: string; label: string }> = {
  pending: { color: 'default', label: '等待下载' },
  downloading: { color: 'processing', label: '下载中' },
  downloaded: { color: 'cyan', label: '已下载' },
  installing: { color: 'processing', label: '安装中' },
  success: { color: 'success', label: '安装成功' },
  failed: { color: 'error', label: '安装失败' },
};

const DashboardPage: React.FC = () => {
  const [tasks, setTasks] = useState<InstallTask[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);

  const fetchTasks = useCallback(async () => {
    setLoading(true);
    try {
      const data = await listTasks(page, 20, { status: statusFilter });
      setTasks(data.items);
      setTotal(data.total);
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  }, [page, statusFilter]);

  useEffect(() => {
    fetchTasks();
    // Auto-refresh every 5 seconds
    const interval = setInterval(fetchTasks, 5000);
    return () => clearInterval(interval);
  }, [fetchTasks]);

  const columns: ColumnsType<InstallTask> = [
    {
      title: 'ID',
      dataIndex: 'id',
      width: 60,
    },
    {
      title: 'APK 名称',
      dataIndex: 'apk_name',
      ellipsis: true,
    },
    {
      title: '目标设备',
      dataIndex: 'device_name',
      width: 150,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 120,
      render: (status: TaskStatus) => {
        const cfg = STATUS_MAP[status] || { color: 'default', label: status };
        return <Badge status={cfg.color as any} text={cfg.label} />;
      },
    },
    {
      title: '重试',
      dataIndex: 'retry_count',
      width: 60,
    },
    {
      title: '错误信息',
      dataIndex: 'error_message',
      ellipsis: true,
      width: 200,
      render: (msg: string) =>
        msg ? <Text type="danger" ellipsis style={{ maxWidth: 180 }}>{msg}</Text> : '-',
    },
    {
      title: '创建时间',
      dataIndex: 'created_at',
      width: 180,
      render: (v: string) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-'),
    },
    {
      title: '更新时间',
      dataIndex: 'updated_at',
      width: 180,
      render: (v: string) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-'),
    },
  ];

  return (
    <Card
      title={
        <Space>
          <span>安装任务列表</span>
          <Tag>{total} 条任务</Tag>
        </Space>
      }
      extra={
        <Select
          allowClear
          placeholder="按状态筛选"
          style={{ width: 140 }}
          value={statusFilter}
          onChange={(val) => {
            setStatusFilter(val);
            setPage(1);
          }}
          options={Object.entries(STATUS_MAP).map(([key, cfg]) => ({
            value: key,
            label: cfg.label,
          }))}
        />
      }
    >
      <Table
        rowKey="id"
        columns={columns}
        dataSource={tasks}
        loading={loading}
        pagination={{
          current: page,
          total,
          pageSize: 20,
          showTotal: (t) => `共 ${t} 条`,
          onChange: setPage,
        }}
        scroll={{ x: 1000 }}
      />
    </Card>
  );
};

export default DashboardPage;
