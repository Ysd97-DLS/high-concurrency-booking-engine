/**
 * 挂号域的术语表：后端枚举值 → 中文显示。
 *
 * 为什么单独抽一个文件：这些映射最初写在 BookView 里，「我的预约」页要显示
 * 医生职称和号别时就得再抄一遍。**同一套业务词汇散落在多个视图里，
 * 迟早会出现同一个枚举值在两个页面显示成不同中文**——
 * 而这类不一致没有任何测试会报错，只有用户会发现。
 *
 * 判断标准很简单：只要一个映射表被第二个组件需要，就该搬到这里来。
 *
 * 注意这里刻意<b>不</b>放预约状态（六状态机）的映射。状态不只是文案，
 * 还带着「允许什么操作」的业务规则，它属于 MineView 的 STATUS_META，
 * 和这里的纯翻译表不是一类东西。混在一起会让人以为状态也只是显示问题。
 */

/** 医生职称。号越难抢的排在越前面，顺序和后端 listOpen 的 ORDER BY FIELD 一致。 */
export const TITLE_TEXT = {
  CHIEF: '主任医师',
  DEPUTY: '副主任医师',
  ATTENDING: '主治医师',
  RESIDENT: '住院医师'
}

/** 职称对应的标签色。主任号最抢手，用最强的视觉权重。 */
export const TITLE_TYPE = {
  CHIEF: 'danger',
  DEPUTY: 'warning',
  ATTENDING: 'primary',
  RESIDENT: 'info'
}

/** 号别。专家号才是真正需要秒杀的那部分号源。 */
export const SLOT_TEXT = {
  NORMAL: '普通号',
  EXPERT: '专家号',
  SPECIAL: '特需号'
}

/** 时段。 */
export const PERIOD_TEXT = {
  AM: '上午',
  PM: '下午'
}

/**
 * 取字典值，缺失时原样返回。
 *
 * 不返回「未知」是刻意的：后端加了新枚举值而前端没跟上时，
 * 显示原始值（如 `SPECIAL_VIP`）能让人立刻看出是哪个值没翻译；
 * 显示「未知」则把线索抹掉了，只能去翻代码。
 */
export const label = (dict, key) => (key == null ? '—' : (dict[key] ?? key))
