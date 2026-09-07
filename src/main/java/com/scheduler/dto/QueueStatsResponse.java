package com.scheduler.dto;

public record QueueStatsResponse(
        long pending, long running, long succeeded, long dead) { }
