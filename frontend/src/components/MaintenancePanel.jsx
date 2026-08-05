import React, { useState, useEffect } from 'react';
import useStore from '../store';

export default function MaintenancePanel({ domain, onClose }) {
  const fetchMaintenanceReport = useStore((s) => s.fetchMaintenanceReport);
  const executeMergeMaintenance = useStore((s) => s.executeMergeMaintenance);
  const deleteOutdatedEntries = useStore((s) => s.deleteOutdatedEntries);
  const entries = useStore((s) => s.entries);

  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState('duplicates'); // 'duplicates' | 'contradictions' | 'outdated'
  const [report, setReport] = useState(null);
  const [executing, setExecuting] = useState(false);
  const [error, setError] = useState(null);
  const [selectedOutdated, setSelectedOutdated] = useState(new Set());

  useEffect(() => {
    loadReport();
  }, [domain]);

  const loadReport = async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await fetchMaintenanceReport(domain);
      setReport(data);
      // 默认选中第一个有数据的 tab
      if (data.duplicates?.length === 0 && data.contradictions?.length > 0) {
        setTab('contradictions');
      } else if (data.duplicates?.length === 0 && data.contradictions?.length === 0 && data.outdated?.length > 0) {
        setTab('outdated');
      }
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  const handleMergeGroup = async (group) => {
    if (!window.confirm(`确定合并这 ${group.entryIndices.length} 条重复条目？\n将保留最详细的一条，删除其余。`)) return;
    setExecuting(true);
    setError(null);
    try {
      await executeMergeMaintenance(domain, [{ entryIndices: group.entryIndices }]);
      await loadReport(); // 刷新报告
    } catch (e) {
      setError(e.message);
    } finally {
      setExecuting(false);
    }
  };

  const handleMergeAll = async () => {
    const groups = report.duplicates.filter((d) => d.entryIndices.length >= 2);
    if (groups.length === 0) return;
    if (!window.confirm(`确定合并全部 ${groups.length} 组重复条目？`)) return;
    setExecuting(true);
    setError(null);
    try {
      await executeMergeMaintenance(domain, groups.map((g) => ({ entryIndices: g.entryIndices })));
      await loadReport();
    } catch (e) {
      setError(e.message);
    } finally {
      setExecuting(false);
    }
  };

  const toggleOutdatedSelection = (idx) => {
    setSelectedOutdated((prev) => {
      const next = new Set(prev);
      if (next.has(idx)) {
        next.delete(idx);
      } else {
        next.add(idx);
      }
      return next;
    });
  };

  const handleDeleteOutdated = async () => {
    if (selectedOutdated.size === 0) return;
    if (!window.confirm(`确定删除选中的 ${selectedOutdated.size} 条过时条目？`)) return;
    setExecuting(true);
    setError(null);
    try {
      await deleteOutdatedEntries(domain, Array.from(selectedOutdated));
      setSelectedOutdated(new Set());
      await loadReport();
    } catch (e) {
      setError(e.message);
    } finally {
      setExecuting(false);
    }
  };

  const handleSelectAllOutdated = () => {
    if (report?.outdated) {
      setSelectedOutdated(new Set(report.outdated.map((o) => o.entryIdx)));
    }
  };

  if (loading) {
    return (
      <div className="modal-overlay" onClick={() => onClose(false)}>
        <div className="modal maintenance-panel" onClick={(e) => e.stopPropagation()}>
          <div className="modal-header">
            <h3>知识维护: {domain}</h3>
            <button className="btn-close" onClick={() => onClose(false)}>&times;</button>
          </div>
          <div className="modal-body">
            <div className="maintenance-loading">AI 正在分析知识库，检测重复、矛盾、过时内容...</div>
          </div>
        </div>
      </div>
    );
  }

  const dupCount = report?.duplicates?.length || 0;
  const contrCount = report?.contradictions?.length || 0;
  const outdatedCount = report?.outdated?.length || 0;
  const totalIssues = dupCount + contrCount + outdatedCount;

  return (
    <div className="modal-overlay" onClick={() => onClose(false)}>
      <div className="modal maintenance-panel" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>知识维护: {domain}</h3>
          <button className="btn-close" onClick={() => onClose(false)}>&times;</button>
        </div>

        <div className="modal-tabs">
          <button
            className={`tab ${tab === 'duplicates' ? 'active' : ''}`}
            onClick={() => setTab('duplicates')}
          >
            重复 ({dupCount})
          </button>
          <button
            className={`tab ${tab === 'contradictions' ? 'active' : ''}`}
            onClick={() => setTab('contradictions')}
          >
            矛盾 ({contrCount})
          </button>
          <button
            className={`tab ${tab === 'outdated' ? 'active' : ''}`}
            onClick={() => setTab('outdated')}
          >
            过时 ({outdatedCount})
          </button>
        </div>

        <div className="modal-body">
          {error && <div className="maintenance-error">{error}</div>}

          {totalIssues === 0 && !error && (
            <div className="maintenance-clean">
              <span className="clean-icon">&#x2705;</span>
              <p>知识库状态良好，未发现重复、矛盾或过时内容。</p>
            </div>
          )}

          {tab === 'duplicates' && (
            <div className="maintenance-duplicates">
              {dupCount === 0 ? (
                <div className="maintenance-hint">未发现重复条目</div>
              ) : (
                <>
                  <p className="maintenance-hint">
                    发现 {dupCount} 组重复或高度相似的条目。建议合并以保持知识库整洁。
                  </p>
                  {report.duplicates.map((dup, di) => (
                    <div key={di} className="duplicate-group">
                      <div className="duplicate-group-header">
                        <span className="duplicate-group-reason">{dup.reason}</span>
                        <button
                          className="btn-merge-single"
                          onClick={() => handleMergeGroup(dup)}
                          disabled={executing}
                        >
                          合并
                        </button>
                      </div>
                      <ul className="duplicate-group-entries">
                        {dup.entryIndices.map((idx) => (
                          <li key={idx}>{entries[idx]?.question || report?.entries?.[idx]?.question || `条目 ${idx}`}</li>
                        ))}
                      </ul>
                    </div>
                  ))}
                  {dupCount > 1 && (
                    <button className="btn-merge-all" onClick={handleMergeAll} disabled={executing}>
                      {executing ? '执行中...' : `合并全部 ${dupCount} 组`}
                    </button>
                  )}
                </>
              )}
            </div>
          )}

          {tab === 'contradictions' && (
            <div className="maintenance-contradictions">
              {contrCount === 0 ? (
                <div className="maintenance-hint">未发现矛盾内容</div>
              ) : (
                <>
                  <p className="maintenance-hint">
                    发现 {contrCount} 对矛盾内容。需要人工确认保留哪条。
                  </p>
                  {report.contradictions.map((contr, ci) => (
                    <div key={ci} className="contradiction-group">
                      <div className="contradiction-description">{contr.description}</div>
                      <div className="contradiction-pair">
                        <div className="contradiction-entry">
                          <span className="contradiction-label">A</span>
                          <span>{contr.question1}</span>
                        </div>
                        <div className="contradiction-vs">VS</div>
                        <div className="contradiction-entry">
                          <span className="contradiction-label">B</span>
                          <span>{contr.question2}</span>
                        </div>
                      </div>
                    </div>
                  ))}
                </>
              )}
            </div>
          )}

          {tab === 'outdated' && (
            <div className="maintenance-outdated">
              {outdatedCount === 0 ? (
                <div className="maintenance-hint">未发现过时条目</div>
              ) : (
                <>
                  <div className="outdated-toolbar">
                    <p className="maintenance-hint">
                      发现 {outdatedCount} 条可能过时的条目（超过 180 天）。
                    </p>
                    <div className="outdated-actions">
                      <button className="btn-select-all" onClick={handleSelectAllOutdated}>
                        全选
                      </button>
                      <button
                        className="btn-delete-selected"
                        onClick={handleDeleteOutdated}
                        disabled={executing || selectedOutdated.size === 0}
                      >
                        {executing ? '删除中...' : `删除选中 (${selectedOutdated.size})`}
                      </button>
                    </div>
                  </div>
                  {report.outdated.map((entry) => (
                    <div key={entry.entryIdx} className="outdated-entry">
                      <label className="outdated-entry-label">
                        <input
                          type="checkbox"
                          checked={selectedOutdated.has(entry.entryIdx)}
                          onChange={() => toggleOutdatedSelection(entry.entryIdx)}
                        />
                        <span className="outdated-question">{entry.question}</span>
                      </label>
                      <span className="outdated-date">{entry.date}</span>
                      <span className="outdated-reason">{entry.reason}</span>
                    </div>
                  ))}
                </>
              )}
            </div>
          )}
        </div>

        <div className="modal-footer">
          <button className="btn-cancel" onClick={() => onClose(false)} disabled={executing}>
            关闭
          </button>
          <button className="btn-refresh" onClick={loadReport} disabled={executing}>
            &#x1f504; 重新检测
          </button>
        </div>
      </div>
    </div>
  );
}
