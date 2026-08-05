import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  ReactFlow,
  Controls,
  Background,
  useNodesState,
  useEdgesState,
  MarkerType,
  Position,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import useStore from '../store';

export default function KnowledgeGraph() {
  const graphData = useStore((s) => s.graphData);
  const graphLoading = useStore((s) => s.graphLoading);
  const fetchGraphData = useStore((s) => s.fetchGraphData);
  const rebuildGraph = useStore((s) => s.rebuildGraph);
  const setShowGraph = useStore((s) => s.setShowGraph);
  const darkMode = useStore((s) => s.darkMode);
  const graphLastBuiltAt = useStore((s) => s.graphLastBuiltAt);
  const knowledgeLastModifiedAt = useStore((s) => s.knowledgeLastModifiedAt);

  // Graph is stale if knowledge was modified after the graph was last built
  const isStale = knowledgeLastModifiedAt && graphLastBuiltAt
    && knowledgeLastModifiedAt > graphLastBuiltAt;

  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const [rebuilding, setRebuilding] = useState(false);

  useEffect(() => {
    fetchGraphData();
  }, [fetchGraphData]);

  // Convert graphData to React Flow format
  useEffect(() => {
    if (!graphData || !graphData.nodes) return;

    // Layout: domain nodes in a circle, concept nodes scattered around their domains
    const domainNodes = graphData.nodes.filter((n) => n.type === 'domain');
    const conceptNodes = graphData.nodes.filter((n) => n.type === 'concept');

    const centerX = 400;
    const centerY = 300;
    const domainRadius = 220;

    const positionedNodes = [
      // Domain nodes in a circle
      ...domainNodes.map((node, i) => {
        const angle = (2 * Math.PI * i) / domainNodes.length - Math.PI / 2;
        return {
          id: node.id,
          type: 'default',
          data: {
            label: (
              <div className="graph-node-label">
                <span className="graph-node-domain">{node.label}</span>
                <span className="graph-node-count">{node.entries}条</span>
              </div>
            ),
          },
          position: {
            x: centerX + domainRadius * Math.cos(angle) - 60,
            y: centerY + domainRadius * Math.sin(angle) - 20,
          },
          className: 'graph-node-domain',
          sourcePosition: Position.Bottom,
          targetPosition: Position.Top,
        };
      }),
      // Concept nodes — position near their connected domain (use index-based scatter)
      ...conceptNodes.map((node, i) => {
        // Find a connected domain to position near
        const relatedEdge = graphData.edges.find(
          (e) => e.source === node.id && e.target.startsWith('domain-')
        );
        let baseX = centerX + 350 * Math.cos((2 * Math.PI * i) / Math.max(conceptNodes.length, 1));
        let baseY = centerY + 350 * Math.sin((2 * Math.PI * i) / Math.max(conceptNodes.length, 1));

        if (relatedEdge) {
          const domainNode = domainNodes.find((d) => d.id === relatedEdge.target);
          if (domainNode) {
            const domainIdx = domainNodes.indexOf(domainNode);
            const angle = (2 * Math.PI * domainIdx) / domainNodes.length - Math.PI / 2;
            const scatter = 100 + (i % 3) * 50;
            baseX = centerX + (domainRadius + scatter) * Math.cos(angle + (i % 2 ? 0.3 : -0.3)) - 40;
            baseY = centerY + (domainRadius + scatter) * Math.sin(angle + (i % 2 ? 0.3 : -0.3)) - 15;
          }
        }

        return {
          id: node.id,
          type: 'default',
          data: { label: node.label },
          position: { x: baseX, y: baseY },
          className: 'graph-node-concept',
          sourcePosition: Position.Top,
          targetPosition: Position.Bottom,
        };
      }),
    ];

    const flowEdges = graphData.edges.map((edge) => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      label: edge.label,
      animated: false,
      style: { stroke: darkMode ? '#60a5fa' : '#3b82f6', strokeWidth: 1.5 },
      labelStyle: { fontSize: 10, fill: darkMode ? '#94a3b8' : '#6b7280' },
      labelBgStyle: { fill: darkMode ? '#1e293b' : '#ffffff', fillOpacity: 0.9 },
      markerEnd: { type: MarkerType.ArrowClosed, color: darkMode ? '#60a5fa' : '#3b82f6' },
    }));

    setNodes(positionedNodes);
    setEdges(flowEdges);
  }, [graphData, darkMode, setNodes, setEdges]);

  const handleRebuild = async () => {
    setRebuilding(true);
    try {
      await rebuildGraph();
    } finally {
      setRebuilding(false);
    }
  };

  const handleBack = () => {
    setShowGraph(false);
  };

  const nodeTypes = useMemo(() => ({}), []);

  if (graphLoading && !graphData) {
    return (
      <div className="knowledge-graph">
        <div className="knowledge-graph-header">
          <button className="btn-back" onClick={handleBack}>返回</button>
          <h2>知识图谱</h2>
        </div>
        <div className="graph-loading">正在构建知识图谱，请稍候...（首次需要 LLM 提取概念）</div>
      </div>
    );
  }

  const isEmpty = !graphData || !graphData.nodes || graphData.nodes.length === 0;

  return (
    <div className="knowledge-graph">
      <div className="knowledge-graph-header">
        <button className="btn-back" onClick={handleBack}>返回</button>
        <h2>知识图谱</h2>
        <div className="graph-info">
          {graphData && (
            <span className="graph-stats">
              {graphData.nodes?.length || 0} 个节点 · {graphData.edges?.length || 0} 条关系
            </span>
          )}
        </div>
        <button
          className="btn-rebuild"
          onClick={handleRebuild}
          disabled={rebuilding}
        >
          {rebuilding ? '重建中...' : '↻ 重建'}
        </button>
      </div>
      {isStale && (
        <div className="graph-stale-banner">
          <span className="graph-stale-icon">⚠️</span>
          <span>知识库有新内容，图谱数据可能不是最新</span>
          <button className="btn-rebuild-small" onClick={handleRebuild} disabled={rebuilding}>
            {rebuilding ? '重建中...' : '立即重建'}
          </button>
        </div>
      )}
      {isEmpty ? (
        <div className="graph-empty">
          <p>知识库为空，无法构建图谱</p>
          <p className="graph-empty-hint">先进行一些对话，系统会自动创建知识域</p>
        </div>
      ) : (
        <div className="graph-canvas">
          <ReactFlow
            nodes={nodes}
            edges={edges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            fitView
            fitViewOptions={{ padding: 0.2 }}
            minZoom={0.3}
            maxZoom={2}
            proOptions={{ hideAttribution: true }}
          >
            <Background color={darkMode ? '#334155' : '#e5e7eb'} gap={20} />
            <Controls showInteractive={false} />
          </ReactFlow>
        </div>
      )}
    </div>
  );
}
