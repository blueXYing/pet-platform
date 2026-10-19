import { HealthRecordFormPage } from './record-form'

// 契约 68 疫苗记录添加页（设计稿 96:2580）：共享表单实现见 record-form.tsx。
export default function PetVaccineRecordForm() {
  return <HealthRecordFormPage kind='VACCINATION' />
}
