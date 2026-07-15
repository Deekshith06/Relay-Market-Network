package com.relaydelivery.service;

import com.relaydelivery.model.Models.Route;
import com.relaydelivery.model.Models.ZoneEdge;
import com.relaydelivery.util.ApiException;

import java.util.*;

public final class RouteOptimizer {
    private record Edge(long to, double distance) {}
    private record Node(long id, double distance) implements Comparable<Node> {
        public int compareTo(Node other) { return Double.compare(distance, other.distance); }
    }
    private final Map<Long, List<Edge>> graph = new HashMap<>();
    private final Set<Long> zones = new HashSet<>();

    public RouteOptimizer(Collection<ZoneEdge> edges) {
        for (ZoneEdge edge : edges) {
            if (edge.from() <= 0 || edge.to() <= 0 || edge.from() == edge.to() ||
                !Double.isFinite(edge.distance()) || edge.distance() <= 0)
                throw new IllegalArgumentException("Route edges require distinct positive zones and distance");
            zones.add(edge.from()); zones.add(edge.to());
            graph.computeIfAbsent(edge.from(), ignored -> new ArrayList<>())
                .add(new Edge(edge.to(), edge.distance()));
        }
    }

    /** Dijkstra: O((V+E) log V) time and O(V+E) space for adjacency, distances and heap. */
    public Route shortest(long source, long target) {
        if (!zones.contains(source) || !zones.contains(target))
            throw ApiException.badRequest("Select valid pickup and drop zones");
        if (source == target) return new Route(0, List.of(source));
        Map<Long, Double> distance = new HashMap<>();
        Map<Long, Long> previous = new HashMap<>();
        PriorityQueue<Node> heap = new PriorityQueue<>();
        distance.put(source, 0d);
        heap.add(new Node(source, 0));

        while (!heap.isEmpty()) {
            Node current = heap.poll();
            if (current.distance() > distance.getOrDefault(current.id(), Double.POSITIVE_INFINITY)) continue;
            if (current.id() == target) break;
            for (Edge edge : graph.getOrDefault(current.id(), List.of())) {
                double candidate = current.distance() + edge.distance();
                if (candidate < distance.getOrDefault(edge.to(), Double.POSITIVE_INFINITY)) {
                    distance.put(edge.to(), candidate);
                    previous.put(edge.to(), current.id());
                    heap.add(new Node(edge.to(), candidate));
                }
            }
        }
        if (!distance.containsKey(target)) throw new ApiException(422, "No route connects the selected zones");
        LinkedList<Long> path = new LinkedList<>();
        for (Long at = target; at != null; at = previous.get(at)) path.addFirst(at);
        return new Route(distance.get(target), List.copyOf(path));
    }
}
