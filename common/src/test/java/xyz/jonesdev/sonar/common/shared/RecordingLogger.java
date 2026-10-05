/*
 * Copyright (C) 2026 Sonar Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package xyz.jonesdev.sonar.common.shared;

import xyz.jonesdev.sonar.api.logger.LoggerWrapper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class RecordingLogger implements LoggerWrapper {
  final List<String> infos = new CopyOnWriteArrayList<>();
  final List<String> warnings = new CopyOnWriteArrayList<>();
  final List<String> errors = new CopyOnWriteArrayList<>();

  @Override
  public void info(final String message, final Object... args) {
    infos.add(buildFullMessage(message, args));
  }

  @Override
  public void warn(final String message, final Object... args) {
    warnings.add(buildFullMessage(message, args));
  }

  @Override
  public void error(final String message, final Object... args) {
    errors.add(buildFullMessage(message, args));
  }
}
