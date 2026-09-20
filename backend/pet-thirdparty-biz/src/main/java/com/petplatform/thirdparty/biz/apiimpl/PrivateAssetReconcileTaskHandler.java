package com.petplatform.thirdparty.biz.apiimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.petplatform.task.core.TaskExecutionContext;
import com.petplatform.task.core.TaskExecutionResult;
import com.petplatform.task.core.TaskHandler;
import com.petplatform.task.core.TaskLease;
import com.petplatform.task.core.TaskRegistration;
import java.time.Duration;
import java.util.Objects;

/** SQL13 shared-worker handler for durable private-object binding and security processing. */
public final class PrivateAssetReconcileTaskHandler implements TaskHandler<Long> {
  public static final String TASK_TYPE = "PRIVATE_ASSET_RECONCILE";
  private final PrivateAssetApiImpl assets;

  public PrivateAssetReconcileTaskHandler(PrivateAssetApiImpl assets) {
    this.assets = Objects.requireNonNull(assets);
  }

  @Override
  public String taskType() {
    return TASK_TYPE;
  }

  @Override
  public TaskExecutionResult execute(TaskExecutionContext context, Long assetId) {
    if (assetId == null || assetId <= 0) return new TaskExecutionResult.Dead("INVALID_ASSET_ID");
    return assets.reconcileAsset(assetId) == PrivateAssetApiImpl.ReconcileResult.COMPLETE
        ? new TaskExecutionResult.Success("PRIVATE_ASSET_RECONCILED")
        : new TaskExecutionResult.Retry(
            "PRIVATE_ASSET_PROVIDER_UNAVAILABLE", Duration.ofSeconds(30));
  }

  public TaskRegistration<Long> registration(ObjectMapper codec) {
    Objects.requireNonNull(codec);
    return new TaskRegistration<>(
        this,
        lease -> decode(codec, lease),
        lease -> "TASK:PRIVATE_ASSET_RECONCILE:" + lease.bizId() + ":0");
  }

  private static Long decode(ObjectMapper codec, TaskLease lease) {
    try {
      var root = codec.readTree(lease.payloadJson());
      String assetId = root.path("assetId").textValue();
      if (assetId == null || !assetId.matches("[1-9][0-9]{0,18}")) {
        throw new IllegalArgumentException("Invalid private asset task payload");
      }
      long decoded = Long.parseLong(assetId);
      if (decoded != lease.bizId())
        throw new IllegalArgumentException("Private asset task binding mismatch");
      return decoded;
    } catch (IllegalArgumentException invalid) {
      throw invalid;
    } catch (Exception invalid) {
      throw new IllegalArgumentException("Invalid private asset task payload", invalid);
    }
  }
}
