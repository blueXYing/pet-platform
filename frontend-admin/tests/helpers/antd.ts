import type { Page } from '@playwright/test';

/**
 * antd Select 没有原生 <select>，验收脚本改为：点 antd 选择器本体开下拉 → 在弹层里按可见文本点选项。
 * 注意：不能点 label（label 转发会开-合双切换）；也不能用 role=option（v6 只给虚拟滚动窗口内的选项挂角色）。
 */
/** 打开某个 antd 下拉（按 label 定位），返回弹层 locator，供直接断言选项禁用态。 */
export async function openDropdown(page: Page, selectLabel: string) {
  await page.getByLabel(selectLabel, { exact: true }).locator('xpath=ancestor::div[contains(@class,"ant-select")][1]').click();
  return page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
}

export async function chooseOption(page: Page, selectLabel: string, optionName: string | RegExp) {
  const selector = page.getByLabel(selectLabel, { exact: true }).locator('xpath=ancestor::div[contains(@class,"ant-select")][1]');
  await selector.click();
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)');
  if (typeof optionName === 'string') await dropdown.getByText(optionName, { exact: true }).click();
  else await dropdown.getByText(optionName).click();
}
