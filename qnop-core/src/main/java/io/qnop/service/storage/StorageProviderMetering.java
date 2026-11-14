/*
 * Copyright (c) 2026-present devtank42 GmbH
 *
 * This file is part of qnop (Qualified Notes on Papers).
 *
 * qnop is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * qnop is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with qnop. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package io.qnop.service.storage;

import io.qnop.observability.metrics.StorageMeters;
import io.qnop.spi.storage.StorageProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Wraps whichever {@link StorageProvider} the context holds in a {@link MeteredStorageProvider}
 * (issue #962), so the built-in S3 adapter and an add-on's replacement (ADR-0002/0003) are measured
 * alike and no caller has to know.
 *
 * <p>The meters are looked up when the provider is created, not when this post-processor is: a
 * post-processor that pulled in the meter registry at its own creation would take the registry out
 * of reach of the post-processors that configure it.
 */
@Component
public class StorageProviderMetering implements BeanPostProcessor {

  private final ObjectProvider<StorageMeters> meters;

  public StorageProviderMetering(ObjectProvider<StorageMeters> meters) {
    this.meters = meters;
  }

  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    if (bean instanceof StorageProvider provider && !(bean instanceof MeteredStorageProvider)) {
      return new MeteredStorageProvider(provider, meters.getObject());
    }
    return bean;
  }
}
