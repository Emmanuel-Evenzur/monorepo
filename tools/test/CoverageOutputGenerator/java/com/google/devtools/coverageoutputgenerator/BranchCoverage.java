// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.coverageoutputgenerator;

import com.google.auto.value.AutoValue;

/**
 * Stores branch coverage information.
 *
 * Corresponds to either a BRDA or BA (Google only) line in an lcov report.
 * <p>BA lines correspond to instances where blockNumber and branchNumber are set to empty Strings and have the form:
 * <pre>BA:[line_number],[taken]</pre>
 * In this case, nrOfExecutions() actually refers to the "taken" value where:
 * <ul>
 * <li>0 = Branch was never evaluated (evaluated() == false)</li>
 * <li>1 = Branch was evaluated but never taken</li>
 * <li>2 = Branch was taken</li>
 * </ul>
 *
 * BRDA lines set have the form
 * <pre>BRDA:[line_number],[block_number],[branch_number],[taken]</pre>
 * where the block and branch numbers are internal identifiers, and taken is either "-" if the branch was never
 * executed or a number indicating how often the branch was taken (which may be 0).
 */
@AutoValue
abstract class BranchCoverage {

  /**
   * Create a BranchCoverage object corresponding to a BA line
   * <pre>BA:[line_number],[taken]</pre>
   * @param lineNumber
   * @param value the taken value, 0, 1, 2
   * @return corresponding BranchCoverage
   */
  static BranchCoverage create(int lineNumber, long value) {
    assert value >= 0 && value < 3;
    return new AutoValue_BranchCoverage(
        lineNumber, /*blockNumber=*/ "", /*branchNumber=*/ "", value > 0, value);
  }

  /**
   * Create a BranchCoverage object corresponding to a BRDA line
   * <pre>BRDA:[line_number],[block_number],[branch_number],[taken]</pre>
   * @param lineNumber
   * @param blockNumber
   * @param branchNumber
   * @param evaluated if this branch was evaluated (taken != "-")
   * @param nrOfExecutions how many times the branch was taken (the value of taken if taken != "-")
   * @return corresponding BranchCoverage
   */
  static BranchCoverage createWithBlockAndBranch(
      int lineNumber,
      String blockNumber,
      String branchNumber,
      boolean evaluated,
      long nrOfExecutions) {
    return new AutoValue_BranchCoverage(
        lineNumber, blockNumber, branchNumber, evaluated, nrOfExecutions);
  }

  /**
   * Merges two given instances of {@link BranchCoverage}.
   *
   * <p>Calling {@code lineNumber()}, {@code blockNumber()} and {@code branchNumber()} must return
   * the same values for {@code first} and {@code second}.
   */
  static BranchCoverage merge(BranchCoverage first, BranchCoverage second) {
    assert first.lineNumber() == second.lineNumber();
    assert first.blockNumber().equals(second.blockNumber());
    assert first.branchNumber().equals(second.branchNumber());
    if (first.blockNumber().isEmpty()) {
      return mergeWithNoBlockAndBranch(first, second);
    }
    return mergeWithBlockAndBranch(first, second);
  }

  private static BranchCoverage mergeWithBlockAndBranch(BranchCoverage first, BranchCoverage second) {
    return createWithBlockAndBranch(
            first.lineNumber(),
            first.blockNumber(),
            first.branchNumber(),
            first.evaluated() || second.evaluated(),
            first.nrOfExecutions() + second.nrOfExecutions());
  }

  private static BranchCoverage mergeWithNoBlockAndBranch(BranchCoverage first, BranchCoverage second) {
    long value = Math.max(first.nrOfExecutions(), second.nrOfExecutions());
    assert value <= 2;
    assert value > 0;
    return create(first.lineNumber(), value);
  }

  abstract int lineNumber();
  // The two numbers below should be -1 for non-gcc emitted coverage (e.g. Java).
  abstract String blockNumber(); // internal gcc ID for the branch

  abstract String branchNumber(); // internal gcc ID for the branch

  abstract boolean evaluated();

  abstract long nrOfExecutions();

  boolean wasExecuted() {
    return blockNumber().isEmpty() ? nrOfExecutions() == 2 : nrOfExecutions() > 0;
  }
}
