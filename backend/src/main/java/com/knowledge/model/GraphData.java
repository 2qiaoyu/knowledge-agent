package com.knowledge.model;

import java.util.List;

/**
 * 知识图谱数据结构。
 */
public record GraphData(List<Node> nodes, List<Edge> edges) {

    /**
     * 图谱节点。
     *
     * @param id      唯一标识
     * @param label   显示名称
     * @param type    节点类型: "domain" 或 "concept"
     * @param entries 关联的问答条目数量
     */
    public record Node(String id, String label, String type, int entries) {
    }

    /**
     * 图谱边（关系）。
     *
     * @param id     唯一标识
     * @param source 源节点 id
     * @param target 目标节点 id
     * @param label  关系描述
     */
    public record Edge(String id, String source, String target, String label) {
    }
}
