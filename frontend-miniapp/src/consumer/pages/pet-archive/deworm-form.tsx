import { HealthRecordFormPage } from './record-form'

// 契约 68 驱虫记录添加页（设计稿 96:3006）：共享表单实现见 record-form.tsx。
export default function PetDewormRecordForm() {
  return <HealthRecordFormPage kind='DEWORMING' />
}
