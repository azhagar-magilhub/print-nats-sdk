package com.magilhub.printnats.queue;

/** A queued print. {@code payloadJson} is re-rendered at dispatch time (freshness is judged then, like legacy). */
public final class PrintJob {
    public String jobId;
    public JobKind kind = JobKind.KOT;
    public String printerId;
    public String payloadJson;
    public JobStatus status = JobStatus.PENDING;
    public int retries;
    public String reason;
    public String category;
    public long createdAt;
    public long updatedAt;

    // Order metadata for status events / Failed Print Queue grouping (display only).
    public String orderId;
    public String orderNo;
    public String sortOrder;
    public String kotNo;
    public String messageId;
    public String source;

    public PrintJob copy() {
        PrintJob j = new PrintJob();
        j.jobId = jobId;
        j.kind = kind;
        j.printerId = printerId;
        j.payloadJson = payloadJson;
        j.status = status;
        j.retries = retries;
        j.reason = reason;
        j.category = category;
        j.createdAt = createdAt;
        j.updatedAt = updatedAt;
        j.orderId = orderId;
        j.orderNo = orderNo;
        j.sortOrder = sortOrder;
        j.kotNo = kotNo;
        j.messageId = messageId;
        j.source = source;
        return j;
    }

    @Override
    public String toString() {
        return "PrintJob{" + jobId + " " + kind + " printer=" + printerId + " " + status + " retries=" + retries
                + " order=" + orderNo + (reason == null ? "" : " reason=" + reason) + "}";
    }
}
