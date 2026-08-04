import React, { useState, useEffect } from 'react';
import useStore from '../store';

export default function DomainOrganizer({ domain, onClose }) {
  const suggestSplit = useStore((s) => s.suggestSplit);
  const suggestMerge = useStore((s) => s.suggestMerge);
  const executeSplit = useStore((s) => s.executeSplit);
  const entries = useStore((s) => s.entries);

  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState('split'); // 'split' | 'merge'
  const [splitGroups, setSplitGroups] = useState([]);
  const [mergeGroups, setMergeGroups] = useState([]);
  const [executing, setExecuting] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    loadSuggestions();
  }, [domain]);

  const loadSuggestions = async () => {
    setLoading(true);
    setError(null);
    try {
      const [split, merge] = await Promise.all([
        suggestSplit(domain),
        suggestMerge(domain),
      ]);
      setSplitGroups(split.groups || []);
      setMergeGroups(merge.duplicates || []);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  // 切换某条目的勾选状态
  const toggleEntry = (groupIdx, entryIdx) => {
    setSplitGroups((prev) =>
      prev.map((g, i) => {
        if (i !== groupIdx) return g;
        const newIndices = g.entryIndices.includes(entryIdx)
          ? g.entryIndices.filter((x) => x !== entryIdx)
          : [...g.entryIndices, entryIdx];
        return { ...g, entryIndices: newIndices };
      })
    );
  };

  // 修改组名
  const updateGroupName = (groupIdx, newName) => {
    setSplitGroups((prev) =>
      prev.map((g, i) => (i === groupIdx ? { ...g, suggestedName: newName } : g))
    );
  };

  // 添加新组
  const addGroup = () => {
    setSplitGroups((prev) => [
      ...prev,
      { suggestedName: '新知识域', entryIndices: [], reason: '手动创建' },
    ]);
  };

  // 删除组
  const removeGroup = (groupIdx) => {
    setSplitGroups((prev) => prev.filter((_, i) => i !== groupIdx));
  };

  // 执行拆分
  const handleExecute = async () => {
    // 过滤掉空组
    const validGroups = splitGroups.filter((g) => g.entryIndices.length > 0);
    if (validGroups.length === 0) {
      return;
    }

    if (
      !window.confirm(
        `确定将「${domain}」拆分为 ${validGroups.length} 个知识域？\n\n${validGroups.map((g) => `  • ${g.suggestedName} (${g.entryIndices.length} 条)`).join('\n')}`
      )
    ) {
      return;
    }

    setExecuting(true);
    setError(null);
    try {
      await executeSplit(domain, validGroups);
      onClose(true); // true = 操作成功，刷新视图
    } catch (e) {
      setError(e.message);
    } finally {
      setExecuting(false);
    }
  };

  if (loading) {
    return (
      <div className="modal-overlay" onClick={() => onClose(false)}>
        <div className="modal domain-organizer" onClick={(e) => e.stopPropagation()}>
          <div className="modal-header">
            <h3>整理知识域: {domain}</h3>
            <button className="btn-close" onClick={() => onClose(false)}>×</button>
          </div>
          <div className="modal-body">
            <div className="organizer-loading">AI 正在分析知识域内容...</div>
          </div>
        </div>
      </div>
    );
  }

  const entryItems = entries || [];
  const unassigned = new Set();
  splitGroups.forEach((g) => g.entryIndices.forEach((i) => unassigned.add(i)));

  return (
    <div className="modal-overlay" onClick={() => onClose(false)}>
      <div className="modal domain-organizer" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>整理知识域: {domain}</h3>
          <button className="btn-close" onClick={() => onClose(false)}>×</button>
        </div>

        <div className="modal-tabs">
          <button
            className={`tab ${tab === 'split' ? 'active' : ''}`}
            onClick={() => setTab('split')}
          >
            拆分 ({splitGroups.length})
          </button>
          <button
            className={`tab ${tab === 'merge' ? 'active' : ''}`}
            onClick={() => setTab('merge')}
          >
            去重 ({mergeGroups.length})
          </button>
        </div>

        <div className="modal-body">
          {error && <div className="organizer-error">{error}</div>}

          {tab === 'split' && (
            <div className="split-panel">
              <p className="organizer-hint">
                AI 建议将「{domain}」拆分为 {splitGroups.length} 个知识域。
                您可以编辑组名、调整分组，然后应用。
              </p>
              {splitGroups.map((group, gi) => (
                <div key={gi} className="split-group">
                  <div className="split-group-header">
                    <input
                      className="split-group-name"
                      value={group.suggestedName}
                      onChange={(e) => updateGroupName(gi, e.target.value)}
                      placeholder="知识域名称"
                    />
                    <span className="split-group-count">{group.entryIndices.length} 条</span>
                    <button
                      className="btn-remove-group"
                      onClick={() => removeGroup(gi)}
                      title="删除此组"
                    >
                      ×
                    </button>
                  </div>
                  {group.reason && <div className="split-group-reason">{group.reason}</div>}
                  <div className="split-group-entries">
                    {entryItems.map((entry, ei) => (
                      <label key={ei} className="entry-checkbox">
                        <input
                          type="checkbox"
                          checked={group.entryIndices.includes(ei)}
                          onChange={() => toggleEntry(gi, ei)}
                        />
                        <span className="entry-checkbox-label">{entry.question}</span>
                      </label>
                    ))}
                  </div>
                </div>
              ))}
              <button className="btn-add-group" onClick={addGroup}>
                + 添加分组
              </button>
            </div>
          )}

          {tab === 'merge' && (
            <div className="merge-panel">
              {mergeGroups.length === 0 ? (
                <div className="organizer-hint">未发现重复条目</div>
              ) : (
                <>
                  <p className="organizer-hint">
                    发现 {mergeGroups.length} 组重复或高度相似的条目。
                    建议手动编辑合并。
                  </p>
                  {mergeGroups.map((dup, di) => (
                    <div key={di} className="merge-group">
                      <div className="merge-group-reason">{dup.reason}</div>
                      <ul className="merge-group-entries">
                        {dup.entryIndices.map((idx) => (
                          <li key={idx}>{entryItems[idx]?.question || `条目 ${idx}`}</li>
                        ))}
                      </ul>
                    </div>
                  ))}
                </>
              )}
            </div>
          )}
        </div>

        <div className="modal-footer">
          <button className="btn-cancel" onClick={() => onClose(false)} disabled={executing}>
            取消
          </button>
          {tab === 'split' && (
            <button
              className="btn-apply"
              onClick={handleExecute}
              disabled={executing || splitGroups.every((g) => g.entryIndices.length === 0)}
            >
              {executing ? '执行中...' : '应用拆分'}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
