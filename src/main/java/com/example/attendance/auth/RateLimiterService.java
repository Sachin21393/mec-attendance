package com.example.attendance.auth;

import com.example.attendance.common.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RateLimiterService {

    private final Map<String, Deque<Instant>> buckets = new ConcurrentHashMap<>();

    public void checkLimit(String bucketKey, int maxRequests, Duration window) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(window);
        Deque<Instant> timestamps = buckets.computeIfAbsent(bucketKey, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= maxRequests) {
                throw new AppException("RATE_LIMITED", "Too many attempts. Please wait a moment and try again.", HttpStatus.TOO_MANY_REQUESTS);
            }
            timestamps.addLast(now);
        }
    }

    public void resetAll() {
        buckets.clear();
    }
}
