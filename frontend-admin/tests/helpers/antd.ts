import { expect, type Locator, type Page } from '@playwright/test';

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
  // 以选择器本体显示的文字核对是否选中（不依赖 antd 内部节点类名，v5/v6 通用）。
  const chosen = (timeout?: number) => typeof optionName === 'string'
    ? expect(selector, `"${selectLabel}" 应已选中「${optionName}」`).toContainText(optionName, timeout ? { timeout } : {})
    : expect(selector, `"${selectLabel}" 应已选中匹配 ${optionName} 的选项`).toHaveText(optionName, timeout ? { timeout } : {});
  // 机器高负载下，弹层入场动画或相邻日期面板的收起动画偶尔会吞掉这一次点击（下拉仍开着、值未变）。
  // 以选择器实际显示的已选项为准：没选上就在同一轮里再选一次（下拉还开着就直接点选项），最多三次。
  for (let attempt = 0; attempt < 3; attempt++) {
    const dropdown = page.locator('.ant-select-dropdown').filter({ visible: true });
    if (attempt === 0 || await dropdown.count() === 0) await selector.click();
    await dropdown.getByText(optionName, typeof optionName === 'string' ? { exact: true } : {}).click();
    try { await chosen(1500); return; } catch { /* 未选中：再选一次 */ }
  }
  await chosen();
}

/** 区域内第 n 个 antd 下拉（没有可见 label 时按序号）：点开后在当前可见弹层里按文本点选。 */
export async function chooseNthOption(page: Page, scope: Locator, index: number, optionName: string) {
  await scope.locator('.ant-select').nth(index).click();
  await page.locator('.ant-select-dropdown').filter({ visible: true }).getByText(optionName, { exact: true }).click();
}
