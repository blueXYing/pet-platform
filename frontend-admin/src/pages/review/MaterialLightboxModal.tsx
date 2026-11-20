import { useEffect, useState } from 'react';
import { Button, Modal, Space, Tag, Tooltip } from 'antd';
import {
  ReloadOutlined,
  RotateLeftOutlined,
  RotateRightOutlined,
  ZoomInOutlined,
  ZoomOutOutlined,
} from '@ant-design/icons';

export type LightboxMaterial = {
  assetId: string;
  label: string;
  category: 'credential' | 'store_photo';
  url: string;
};

export function MaterialLightboxModal({
  open,
  activeAssetId,
  items,
  onSelect,
  onClose,
}: {
  open: boolean;
  activeAssetId: string | null;
  items: LightboxMaterial[];
  onSelect: (assetId: string) => void;
  onClose: () => void;
}) {
  const [scale, setScale] = useState(1);
  const [rotation, setRotation] = useState(0);

  useEffect(() => {
    setScale(1);
    setRotation(0);
  }, [activeAssetId, open]);

  useEffect(() => {
    if (!open) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [open, onClose]);

  const current = items.find(item => item.assetId === activeAssetId) ?? items[0];
  const zoomIn = () => setScale(value => Math.min(3, Number((value + 0.25).toFixed(2))));
  const zoomOut = () => setScale(value => Math.max(0.5, Number((value - 0.25).toFixed(2))));
  const rotateLeft = () => setRotation(value => (value - 90 + 360) % 360);
  const rotateRight = () => setRotation(value => (value + 90) % 360);
  const resetView = () => { setScale(1); setRotation(0); };

  return (
    <Modal
      open={open && current !== undefined}
      onCancel={onClose}
      footer={null}
      width={920}
      centered
      destroyOnHidden
      title={current ? (
        <Space size={8} wrap>
          <span>{current.label}</span>
          <Tag color={current.category === 'credential' ? 'blue' : 'default'}>
            {current.category === 'credential' ? '资质证件' : '门店照片'}
          </Tag>
          <span className="review-muted-code">资产 {current.assetId}</span>
        </Space>
      ) : '材料查验'}
    >
      {current && (
        <div className="review-lightbox">
          <div className="review-lightbox-toolbar">
            <Space size={8} wrap>
              <Tooltip title="放大">
                <Button size="small" icon={<ZoomInOutlined aria-hidden="true" />} onClick={zoomIn} aria-label="放大图片">放大</Button>
              </Tooltip>
              <Tooltip title="缩小">
                <Button size="small" icon={<ZoomOutOutlined aria-hidden="true" />} onClick={zoomOut} aria-label="缩小图片">缩小</Button>
              </Tooltip>
              <Tooltip title="逆时针旋转 90°">
                <Button size="small" icon={<RotateLeftOutlined aria-hidden="true" />} onClick={rotateLeft} aria-label="左旋图片">左旋</Button>
              </Tooltip>
              <Tooltip title="顺时针旋转 90°">
                <Button size="small" icon={<RotateRightOutlined aria-hidden="true" />} onClick={rotateRight} aria-label="右旋图片">右旋</Button>
              </Tooltip>
              <Tooltip title="恢复默认比例与角度">
                <Button size="small" icon={<ReloadOutlined aria-hidden="true" />} onClick={resetView} aria-label="重置视图">重置</Button>
              </Tooltip>
              <Tag>{Math.round(scale * 100)}%</Tag>
              {rotation !== 0 && <Tag>{rotation}°</Tag>}
            </Space>
            <span className="review-lightbox-hint">一次性水印读取结果 · 仅供本次核对使用</span>
          </div>

          <div className="review-lightbox-stage">
            <img
              src={current.url}
              alt={`${current.label} 高清查验`}
              className="review-lightbox-image"
              style={{ transform: `scale(${scale}) rotate(${rotation}deg)` }}
            />
          </div>

          {items.length > 1 && (
            <div className="review-lightbox-strip">
              {items.map(item => (
                <button
                  key={item.assetId}
                  type="button"
                  className={`review-lightbox-thumb${item.assetId === current.assetId ? ' is-active' : ''}`}
                  onClick={() => onSelect(item.assetId)}
                >
                  <img src={item.url} alt={item.label} />
                  <span>{item.label}</span>
                </button>
              ))}
            </div>
          )}
        </div>
      )}
    </Modal>
  );
}
