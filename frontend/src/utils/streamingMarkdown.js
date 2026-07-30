/**
 * 流式 Markdown 修复工具
 *
 * 在流式输出过程中，Markdown 标记可能不完整（如未闭合的粗体、代码块等）。
 * 本模块提供修复函数，在渲染前补全未闭合的标记，确保 react-markdown 能正确解析。
 */

/**
 * 修复不完整的 Markdown 标记。
 * 处理以下情况：
 * 1. 未闭合的代码块（``` 或 ~~~）
 * 2. 未闭合的粗体/斜体（**、*、__、_）
 * 3. 未闭合的链接/图片 [text](url 或 ![alt](url
 * 4. 未闭合的行内代码（`）
 *
 * @param {string} text - 原始 Markdown 文本
 * @returns {string} - 修复后的文本
 */
export function fixIncompleteMarkdown(text) {
  if (!text || text.length === 0) return text;

  let result = text;

  // 1. 修复未闭合的代码块
  result = fixUnclosedCodeFences(result);

  // 2. 修复未闭合的行内代码
  result = fixUnclosedInlineCode(result);

  // 3. 修复未闭合的粗体 (**)
  result = fixUnclosedEmphasis(result, '**');

  // 4. 修复未闭合的下划线粗体 (__)
  result = fixUnclosedEmphasis(result, '__');

  // 5. 修复未闭合的斜体 (*) - 排除已经是 ** 的情况
  result = fixUnclosedAsteriskItalic(result);

  // 6. 修复未闭合的下划线斜体 (_)
  result = fixUnclosedUnderscoreItalic(result);

  // 7. 修复未闭合的链接和图片
  result = fixUnclosedLinks(result);

  return result;
}

/**
 * 修复未闭合的代码块。
 * 统计 ``` 和 ~~~ 的数量，如果为奇数则在末尾添加闭合标记。
 */
function fixUnclosedCodeFences(text) {
  // 匹配 ``` 或 ~~~ 代码块（行首位置）
  const fenceRegex = /^(```|~~~)/gm;
  const fences = text.match(fenceRegex);

  if (fences && fences.length % 2 !== 0) {
    // 最后一个未闭合的 fence
    const lastFence = fences[fences.length - 1];
    // 检查最后一个 fence 之后是否有换行
    const lastFenceIndex = text.lastIndexOf(lastFence);
    const afterFence = text.substring(lastFenceIndex + lastFence.length);

    if (afterFence.endsWith('\n')) {
      return text + lastFence;
    } else {
      return text + '\n' + lastFence;
    }
  }

  return text;
}

/**
 * 修复未闭合的行内代码（单个反引号）。
 * 需要排除已经由代码块处理的反引号。
 */
function fixUnclosedInlineCode(text) {
  // 移除代码块内容后计算行内代码反引号
  const withoutCodeBlocks = text.replace(/```[\s\S]*?```/g, '').replace(/~~~[\s\S]*?~~~/g, '');

  // 统计单个反引号（非双反引号）
  const singleBackticks = (withoutCodeBlocks.match(/(?<!`)`(?!`)/g) || []);

  if (singleBackticks.length % 2 !== 0) {
    return text + '`';
  }

  return text;
}

/**
 * 修复未闭合的强调标记（** 或 __）
 */
function fixUnclosedEmphasis(text, marker) {
  const count = (text.split(marker).length - 1);

  if (count % 2 !== 0) {
    return text + marker;
  }

  return text;
}

/**
 * 修复未闭合的星号斜体（单个 *，排除 ** 粗体）
 */
function fixUnclosedAsteriskItalic(text) {
  // 移除 ** 后统计单个 *
  const withoutBold = text.replace(/\*\*/g, '');
  // 匹配单个 *（前后都不是 *）
  const singleAsterisks = withoutBold.match(/(?<!\*)\*(?!\*)/g) || [];

  if (singleAsterisks.length % 2 !== 0) {
    return text + '*';
  }

  return text;
}

/**
 * 修复未闭合的下划线斜体（单个 _，排除 __ 粗体）
 */
function fixUnclosedUnderscoreItalic(text) {
  // 移除 __ 后统计单个 _
  const withoutBold = text.replace(/__/g, '');
  // 匹配单个 _（前后都不是 _）
  const singleUnderscores = withoutBold.match(/(?<!_)_(?!_)/g) || [];

  if (singleUnderscores.length % 2 !== 0) {
    return text + '_';
  }

  return text;
}

/**
 * 修复未闭合的链接 [text](url 和图片 ![alt](url
 *
 * 策略：找到最后一个 [ 或 ![，检查其后是否有对应的 ] 和 (...)
 * 如果没有完整闭合，则在末尾补全 ) 或移除未完成的部分。
 */
function fixUnclosedLinks(text) {
  let result = text;

  // 查找最后一个 [ 或 ![
  const lastOpen = result.lastIndexOf('[');
  const lastExclaimOpen = result.lastIndexOf('![');
  const lastOpenPos = Math.max(lastOpen, lastExclaimOpen);

  if (lastOpenPos < 0) return result; // 没有 [ 或 ![

  const afterBracket = result.substring(lastOpenPos);

  // 检查这个 [ 后面是否有 ]
  // 完整链接格式: [text](url) 或 ![alt](url)
  const bracketCloseIdx = afterBracket.indexOf(']');

  if (bracketCloseIdx < 0) {
    // [ 没有闭合，移除未完成的部分
    result = result.substring(0, lastOpenPos);
    return result;
  }

  // 有 ]，检查 ] 后面是否有 (
  const afterCloseBracket = afterBracket.substring(bracketCloseIdx + 1);

  if (afterCloseBracket.startsWith('(')) {
    // 有 (，检查是否有对应的 )
    if (!afterCloseBracket.includes(')', 1)) {
      // ( 没有闭合，补全 )
      result = result + ')';
    }
  }
  // 否则是引用式链接或普通文本，不做处理

  return result;
}

/**
 * 检测文本中是否有未闭合的 Markdown 标记。
 * 用于判断是否需要显示"正在输入"指示器。
 */
export function hasIncompleteMarkdown(text) {
  if (!text) return false;

  // 检查代码块
  const fenceRegex = /^(```|~~~)/gm;
  const fences = text.match(fenceRegex);
  if (fences && fences.length % 2 !== 0) return true;

  // 检查行内代码
  const withoutCodeBlocks = text.replace(/```[\s\S]*?```/g, '').replace(/~~~[\s\S]*?~~~/g, '');
  const singleBackticks = (withoutCodeBlocks.match(/(?<!`)`(?!`)/g) || []);
  if (singleBackticks.length % 2 !== 0) return true;

  // 检查粗体
  if ((text.split('**').length - 1) % 2 !== 0) return true;
  if ((text.split('__').length - 1) % 2 !== 0) return true;

  return false;
}
