import React, { useEffect, useMemo, useRef, useState } from "react";
import { useParams } from "react-router-dom";
import {
  Card,
  Typography,
  Button,
  Upload,
  Table,
  Popconfirm,
  Space,
  message,
  Empty,
  Alert,
} from "antd";
import {
  BookOutlined,
  UploadOutlined,
  DeleteOutlined,
  FileOutlined,
} from "@ant-design/icons";
import type { UploadProps } from "antd";
import { useKnowledgeBases } from "../../hooks/useKnowledgeBases.ts";
import { useDocuments } from "../../hooks/useDocuments.ts";
import { uploadDocument, type DocumentVO } from "../../api/api.ts";

const { Title, Text, Paragraph } = Typography;

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError";
}

const KnowledgeBaseView: React.FC = () => {
  const { knowledgeBaseId } = useParams<{ knowledgeBaseId?: string }>();
  const {
    knowledgeBases,
    loading: knowledgeBasesLoading,
    error: knowledgeBasesError,
    refreshKnowledgeBases,
  } = useKnowledgeBases();
  const {
    documents,
    loading,
    error: documentsError,
    refreshDocuments,
    deleteDocument,
  } = useDocuments(knowledgeBaseId);

  const [uploading, setUploading] = useState(false);
  const uploadControllerRef = useRef<AbortController | null>(null);

  useEffect(
    () => () => {
      const controller = uploadControllerRef.current;
      uploadControllerRef.current = null;
      controller?.abort();
    },
    [],
  );

  // 查找当前知识库的详细信息
  const currentKnowledgeBase = useMemo(() => {
    if (!knowledgeBaseId) return null;
    return (
      knowledgeBases.find((kb) => kb.knowledgeBaseId === knowledgeBaseId) ||
      null
    );
  }, [knowledgeBaseId, knowledgeBases]);

  // 处理文件上传
  const handleUpload: UploadProps["customRequest"] = async (options) => {
    const { file, onSuccess, onError } = options;

    if (!knowledgeBaseId) {
      message.error("请先选择知识库");
      return;
    }

    setUploading(true);
    uploadControllerRef.current?.abort();
    const controller = new AbortController();
    uploadControllerRef.current = controller;

    try {
      await uploadDocument(knowledgeBaseId, file as File, controller.signal);
      message.success("文档上传成功");
      await refreshDocuments();
      onSuccess?.(file);
    } catch (error) {
      if (isAbortError(error)) return;
      message.error(error instanceof Error ? error.message : "上传失败");
      onError?.(error as Error);
    } finally {
      if (uploadControllerRef.current === controller) {
        uploadControllerRef.current = null;
        setUploading(false);
      }
    }
  };

  // 格式化文件大小
  const formatFileSize = (bytes: number): string => {
    if (bytes === 0) return "0 B";
    const k = 1024;
    const sizes = ["B", "KB", "MB", "GB"];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return Math.round((bytes / Math.pow(k, i)) * 100) / 100 + " " + sizes[i];
  };

  // 表格列定义
  const columns = [
    {
      title: "文件名",
      dataIndex: "filename",
      key: "filename",
      render: (text: string) => (
        <Space>
          <FileOutlined />
          <span>{text}</span>
        </Space>
      ),
    },
    {
      title: "类型",
      dataIndex: "filetype",
      key: "filetype",
      width: 120,
    },
    {
      title: "大小",
      dataIndex: "size",
      key: "size",
      width: 120,
      render: (size: number) => formatFileSize(size),
    },
    {
      title: "操作",
      key: "action",
      width: 100,
      render: (_: unknown, record: DocumentVO) => (
        <Popconfirm
          title="确定要删除这个文档吗？"
          description="删除后将无法恢复"
          onConfirm={async () => {
            try {
              await deleteDocument(record.id);
              message.success("文档已删除");
            } catch (error) {
              message.error(
                error instanceof Error ? error.message : "删除失败",
              );
              throw error;
            }
          }}
          okText="确定"
          cancelText="取消"
        >
          <Button type="text" danger icon={<DeleteOutlined />} size="small">
            删除
          </Button>
        </Popconfirm>
      ),
    },
  ];

  // 未选择知识库时的提示
  if (!knowledgeBaseId) {
    return (
      <div className="flex flex-col h-full items-center justify-center p-6">
        <Empty
          image={<BookOutlined className="text-6xl text-gray-300" />}
          description={
            <div className="mt-4">
              <Title level={4} type="secondary">
                未选择知识库
              </Title>
              <Text type="secondary" className="text-sm">
                请从左侧知识库列表中选择一个知识库查看详情
              </Text>
            </div>
          }
        />
      </div>
    );
  }

  if (knowledgeBasesLoading && !currentKnowledgeBase) {
    return (
      <div className="flex h-full items-center justify-center text-gray-400">
        正在加载知识库...
      </div>
    );
  }

  if (knowledgeBasesError && !currentKnowledgeBase) {
    return (
      <div className="flex h-full items-center justify-center p-6">
        <Alert
          showIcon
          type="error"
          message="知识库加载失败"
          description={knowledgeBasesError.message}
          action={
            <Button onClick={() => void refreshKnowledgeBases()}>重试</Button>
          }
        />
      </div>
    );
  }

  // 知识库不存在
  if (!currentKnowledgeBase) {
    return (
      <div className="flex flex-col h-full items-center justify-center p-6">
        <Empty
          description={
            <div className="mt-4">
              <Title level={4} type="secondary">
                知识库不存在
              </Title>
              <Text type="secondary" className="text-sm">
                请检查知识库 ID 是否正确
              </Text>
            </div>
          }
        />
      </div>
    );
  }

  // 显示知识库详情和文档列表
  return (
    <div className="flex flex-col h-full p-6 overflow-y-auto">
      <div className="max-w-6xl w-full mx-auto">
        <div className="mb-3">
          <Card>
            <div className="flex items-start gap-4">
              <div className="w-16 h-16 rounded-lg bg-gradient-to-br from-blue-200 to-purple-200 flex items-center justify-center text-3xl shrink-0">
                <BookOutlined />
              </div>
              <div className="flex-1">
                <Title level={3} className="mb-2">
                  {currentKnowledgeBase.name}
                </Title>
                {currentKnowledgeBase.description && (
                  <Paragraph className="text-gray-600 mb-0">
                    {currentKnowledgeBase.description}
                  </Paragraph>
                )}
                <Text type="secondary" className="text-sm">
                  知识库 ID: {currentKnowledgeBase.knowledgeBaseId}
                </Text>
              </div>
            </div>
          </Card>
        </div>
        {/* 知识库信息卡片 */}

        <div className="mb-3">
          {/* 上传文档区域 */}
          <Card title="上传文档">
            <Space wrap>
              <Upload
                customRequest={handleUpload}
                showUploadList={false}
                accept=".md,text/markdown"
                disabled={uploading}
              >
                <Button
                  type="primary"
                  icon={<UploadOutlined />}
                  loading={uploading}
                  size="large"
                >
                  选择文件上传
                </Button>
              </Upload>
              {uploading && (
                <Button
                  size="large"
                  onClick={() => {
                    const controller = uploadControllerRef.current;
                    uploadControllerRef.current = null;
                    controller?.abort();
                    setUploading(false);
                    message.info("已取消上传");
                  }}
                >
                  取消上传
                </Button>
              )}
            </Space>
            <Text type="secondary" className="block mt-2 text-xs">
              支持格式: Markdown
            </Text>
          </Card>
        </div>

        <div className="mb-3">
          {/* 文档列表 */}
          <Card title={`文档列表 (${documents.length})`}>
            {documentsError ? (
              <Alert
                showIcon
                type="error"
                message="文档列表加载失败"
                description={documentsError.message}
                action={
                  <Button size="small" onClick={() => void refreshDocuments()}>
                    重试
                  </Button>
                }
              />
            ) : loading ? (
              <div className="text-center py-8">
                <Text type="secondary">加载中...</Text>
              </div>
            ) : documents.length === 0 ? (
              <Empty
                description={<Text type="secondary">暂无文档，请上传文档</Text>}
              />
            ) : (
              <Table
                columns={columns}
                dataSource={documents}
                rowKey="id"
                scroll={{ x: 640 }}
                pagination={{
                  pageSize: 10,
                  // showSizeChanger: true,
                  showTotal: (total) => `共 ${total} 条`,
                }}
              />
            )}
          </Card>
        </div>
      </div>
    </div>
  );
};

export default KnowledgeBaseView;
