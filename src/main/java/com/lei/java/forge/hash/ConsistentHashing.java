package com.lei.java.forge.hash;

import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * <p>
 * 一致性 hash 实现
 * </p>
 *
 * @author 伍磊
 */
public class ConsistentHashing {

    // 使用 TreeMap 模拟哈希环，Key 是哈希值，Value 是物理节点名
    private final SortedMap<Integer, String> ring = new TreeMap<>();
    // 虚拟节点的数量（每个真实节点对应多少个虚拟节点）
    private final int numberOfReplicas;

    /**
     * 构造函数
     *
     * @param numberOfReplicas 虚拟节点的数量
     * @param nodes            初始的物理节点列表
     */
    public ConsistentHashing(int numberOfReplicas, Collection<String> nodes) {
        this.numberOfReplicas = numberOfReplicas;
        for (String node : nodes) {
            addNode(node);
        }
    }

    /**
     * 添加一个物理节点，同时会添加其对应的所有虚拟节点
     *
     * @param node 物理节点名，如 IP 地址
     */
    public void addNode(String node) {
        for (int i = 0; i < numberOfReplicas; i++) {
            // 为每个虚拟节点计算哈希值，并放入环中
            // 虚拟节点的名称可以简单地通过 "物理节点名#i" 来区分
            int hash = getHash(node + "#" + i);
            ring.put(hash, node);
        }
    }

    /**
     * 删除一个物理节点，同时删除其所有虚拟节点
     *
     * @param node 物理节点名
     */
    public void removeNode(String node) {
        for (int i = 0; i < numberOfReplicas; i++) {
            int hash = getHash(node + "#" + i);
            ring.remove(hash);
        }
    }

    /**
     * 根据给定的 key，计算出应该路由到的物理节点
     *
     * @param key 数据 key
     * @return 物理节点名
     */
    public String getNode(String key) {
        if (ring.isEmpty()) {
            return null;
        }
        int hash = getHash(key);

        // 如果哈希值没有落在任何一个虚拟节点的哈希值上
        // tailMap(hash) 会返回一个视图，其中所有键都大于或等于 hash
        if (!ring.containsKey(hash)) {
            SortedMap<Integer, String> tailMap = ring.tailMap(hash);
            // 如果 tailMap 为空，说明该 key 的哈希值超过了所有节点的哈希值
            // 这时我们认为它“环绕”了，应该由环上的第一个节点处理
            // 否则，就由 tailMap 的第一个节点处理
            hash = tailMap.isEmpty() ? ring.firstKey() : tailMap.firstKey();
        }
        // 返回对应的物理节点
        return ring.get(hash);
    }

    /**
     * 一个简单的哈希函数
     *
     * @param key 字符串
     * @return 哈希值
     */
    private int getHash(String key) {
        // 使用 Guava 的 32 位 MurmurHash 算法
        // 并确保结果为正数
        return Hashing.murmur3_32_fixed().hashString(key, StandardCharsets.UTF_8).asInt() &
                0x7fffffff;
    }

    public static void main(String[] args) {
        // 1. 初始化
        List<String> initialNodes = List.of("192.168.1.1", "192.168.1.2", "192.168.1.3");
        // 每个物理节点创建 100 个虚拟节点，以保证负载均衡
        ConsistentHashing consistentHashing = new ConsistentHashing(100, initialNodes);

        // 2. 生成一些测试数据 key
        List<String> testKeys = IntStream.range(0, 10000)
                .mapToObj(i -> "test-key-" + i)
                .toList();

        System.out.println("---------- 初始状态（3个节点） ----------");
        // 记录每个 key 初始时映射到了哪个节点
        List<String> initialMapping = testKeys.stream()
                .map(consistentHashing::getNode)
                .collect(Collectors.toList());
        printNodeDistribution(initialMapping, initialNodes);


        // 3. 增加一个节点
        System.out.println("\n---------- 增加节点 192.168.1.4 ----------");
        String newNode = "192.168.1.4";
        consistentHashing.addNode(newNode);
        List<String> newMappingAfterAdd = testKeys.stream()
                .map(consistentHashing::getNode)
                .collect(Collectors.toList());
        printNodeDistribution(newMappingAfterAdd,
                List.of("192.168.1.1", "192.168.1.2", "192.168.1.3", newNode));

        // 4. 统计因增加节点而发生变化的 key 的数量
        long changedCount = 0;
        for (int i = 0; i < testKeys.size(); i++) {
            if (!initialMapping.get(i).equals(newMappingAfterAdd.get(i))) {
                changedCount++;
            }
        }
        System.out.printf("增加节点后，%d 个 key 中有 %d 个发生了迁移 (%.2f%%)\n",
                testKeys.size(), changedCount, (double) changedCount / testKeys.size() * 100);


        // 5. 删除一个节点
        System.out.println("\n---------- 删除节点 192.168.1.1 ----------");
        String removedNode = "192.168.1.1";
        consistentHashing.removeNode(removedNode);
        List<String> newMappingAfterRemove = testKeys.stream()
                .map(consistentHashing::getNode)
                .collect(Collectors.toList());
        printNodeDistribution(newMappingAfterRemove,
                List.of("192.168.1.2", "192.168.1.3", newNode));

        // 6. 统计因增加节点而发生变化的 key 的数量
        changedCount = 0;
        for (int i = 0; i < testKeys.size(); i++) {
            if (!newMappingAfterRemove.get(i).equals(newMappingAfterAdd.get(i))) {
                changedCount++;
            }
        }
        System.out.printf("删除节点后，%d 个 key 中有 %d 个发生了迁移 (%.2f%%)\n",
                testKeys.size(), changedCount, (double) changedCount / testKeys.size() * 100);

    }

    private static void printNodeDistribution(List<String> mappings, Collection<String> nodes) {
        System.out.println("数据在各节点上的分布情况:");
        nodes.forEach(node -> {
            long count = mappings.stream().filter(m -> m.equals(node)).count();
            System.out.printf("  - 节点 %s: %d 个 key\n", node, count);
        });
    }
}
