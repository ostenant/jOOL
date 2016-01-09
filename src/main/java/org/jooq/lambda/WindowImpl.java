/**
 * Copyright (c) 2014-2016, Data Geekery GmbH, contact@datageekery.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jooq.lambda;

import java.util.Collection;

import static java.util.Comparator.naturalOrder;
import static java.util.Comparator.comparing;
import static java.util.Collections.binarySearch;
import static org.jooq.lambda.Seq.seq;
import static org.jooq.lambda.tuple.Tuple.tuple;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import org.jooq.lambda.tuple.Tuple2;

/**
 * @author Lukas Eder
 */
class WindowImpl<T> implements Window<T> {
    
    final Tuple2<T, Long>       value;
    final int                   index;
    final Partition<T>          partition;
    final Comparator<? super T> order;
    final long                  lower;
    final long                  upper;

    WindowImpl(
        Tuple2<T, Long> value,
        Partition<T> partition, 
        WindowSpecification<T> specification
    ) {
        this.value = value;
        this.partition = partition;
        this.order = specification.order().orElse((Comparator<? super T>) naturalOrder());
        this.lower = specification.lower();
        this.upper = specification.upper();
        
        int i = specification.order().isPresent()
              ? binarySearch(partition.list, value, comparing((Tuple2<T, Long> t) -> t.v1, specification.order().get()).thenComparing(t -> t.v2))
              : binarySearch(partition.list, value, comparing(t -> t.v2));
        this.index = (i >= 0 ? i : -i - 1);
    }

    // Accessor methods
    // -------------------------------------------------------------------------
    
    @Override
    public T value() {
        return value.v1;
    }
    
    @Override
    public Seq<T> window() {
        return Seq.seq(partition.list.subList(lower(), upper() + 1)).map(t -> t.v1);
    }

    // Utilities
    // -------------------------------------------------------------------------
    
    private int lower() {
        // TODO: What about under/overflows?
        return lower == Long.MIN_VALUE ? 0 : (int) Math.max(0L, index + lower);
    }
    
    private boolean lowerInPartition() {
        // TODO: What about under/overflows?
        return lower == Long.MIN_VALUE || (index + lower >= 0L && index + lower < partition.list.size());
    }
    
    private int upper() {
        // TODO: What about under/overflows?
        return upper == Long.MAX_VALUE ? partition.list.size() - 1 : (int) Math.min(partition.list.size() - 1, (index + upper));
    }
    
    private boolean upperInPartition() {
        // TODO: What about under/overflows?
        return upper == Long.MAX_VALUE || (index + upper >= 0L && index + upper < partition.list.size());
    }
    
    private boolean completePartition() {
        return count() == partition.list.size();
    }
    
    // Ranking functions
    // -------------------------------------------------------------------------
    
    @Override
    public long rowNumber() {
        return (long) index;
    }

    @Override
    public long rank() {
        return seq(partition.list).map(t -> t.v1).collect(Agg.rank(value.v1, order)).get();
    }

    @Override
    public long denseRank() {
        return seq(partition.list).map(t -> t.v1).collect(Agg.denseRank(value.v1, order)).get();
    }

    @Override
    public double percentRank() {
        return ((double) rank()) / ((double) (partition.list.size() - 1));
    }

    @Override
    public long ntile(long bucket) {
        return (bucket * rowNumber() / partition.list.size());
    }

    @Override
    public Optional<T> lead() {
        return lead(1);
    }

    @Override
    public Optional<T> lead(long lead) {
        return lead0(lead);
    }

    @Override
    public Optional<T> lag() {
        return lag(1);
    }

    @Override
    public Optional<T> lag(long lag) {
        return lead0(-lag);
    }

    private Optional<T> lead0(long lead) {
        if (lead == 0)
            return Optional.of(value.v1);
        else if (index + lead >= 0 && index + lead < partition.list.size())
            return Optional.of(partition.list.get(index + (int) lead).v1);
        else
            return Optional.empty();
    }

    @Override
    public Optional<T> firstValue() {
        return firstValue(t -> t);
    }

    @Override
    public <U> Optional<U> firstValue(Function<? super T, ? extends U> function) {
        return lowerInPartition()
             ? Optional.of(function.apply(partition.list.get(lower()).v1))
             : upperInPartition()
             ? Optional.of(function.apply(partition.list.get(0).v1))
             : Optional.empty();
    }

    @Override
    public Optional<T> lastValue() {
        return lastValue(t -> t);
    }

    @Override
    public <U> Optional<U> lastValue(Function<? super T, ? extends U> function) {
        return upperInPartition()
             ? Optional.of(function.apply(partition.list.get(upper()).v1))
             : lowerInPartition()
             ? Optional.of(function.apply(partition.list.get(partition.list.size() - 1).v1))
             : Optional.empty();
    }

    @Override
    public Optional<T> nthValue(long n) {
        return nthValue(n, t -> t);
    }

    @Override
    public <U> Optional<U> nthValue(long n, Function<? super T, ? extends U> function) {
        return lower() + n <= upper()
             ? Optional.of(function.apply(partition.list.get(lower() + (int) n).v1))
             : Optional.empty();
    }
    
    // Aggregate functions
    // -------------------------------------------------------------------------
    
    @Override
    public long count() {
        return 1 + upper() - lower();
    }

    @Override
    public long countDistinct() {
        return partition.cacheIf(completePartition(), "countDistinct", () -> window().countDistinct());
    }

    @Override
    public <U> long countDistinctBy(Function<? super T, ? extends U> function) {
        return partition.cacheIf(completePartition(), () -> tuple("countDistinctBy", function), () -> window().countDistinctBy(function));
    }

    @Override
    public Optional<T> sum() {
        return partition.cacheIf(completePartition(), "sum", () -> window().sum());
    }

    @Override
    public <U> Optional<U> sum(Function<? super T, ? extends U> function) {
        return partition.cacheIf(completePartition(), () -> tuple("sum", function), () -> window().sum(function));
    }

    @Override
    public int sumInt(ToIntFunction<? super T> function) {
        return partition.cacheIf(completePartition(), () -> tuple("sumInt", function), () -> window().sumInt(function));
    }

    @Override
    public long sumLong(ToLongFunction<? super T> function) {
        return partition.cacheIf(completePartition(), () -> tuple("sumLong", function), () -> window().sumLong(function));
    }

    @Override
    public double sumDouble(ToDoubleFunction<? super T> function) {
        return partition.cacheIf(completePartition(), () -> tuple("sumDouble", function), () -> window().sumDouble(function));
    }

    @Override
    public Optional<T> avg() {
        return partition.cacheIf(completePartition(), "avg", () -> window().avg());
    }

    @Override
    public <U> Optional<U> avg(Function<? super T, ? extends U> function) {
        return partition.cacheIf(completePartition(), () -> tuple("avg", function), () -> window().avg(function));
    }

    @Override
    public double avgInt(ToIntFunction<? super T> function) {
        return partition.cacheIf(completePartition(), () -> tuple("avgInt", function), () -> window().avgInt(function));
    }

    @Override
    public double avgLong(ToLongFunction<? super T> function) {
        return partition.cacheIf(completePartition(), () -> tuple("avgLong", function), () -> window().avgLong(function));
    }

    @Override
    public double avgDouble(ToDoubleFunction<? super T> function) {
        return partition.cacheIf(completePartition(), () -> tuple("avgDouble", function), () -> window().avgDouble(function));
    }

    @Override
    public Optional<T> min() {
        return partition.cacheIf(completePartition(), "min", () -> window().min((Comparator) naturalOrder()));
    }

    @Override
    public Optional<T> min(Comparator<? super T> comparator) {
        return partition.cacheIf(completePartition(), () -> tuple("min", comparator), () -> window().min(comparator));
    }

    @Override
    public <U extends Comparable<? super U>> Optional<U> min(Function<? super T, ? extends U> function) {
        return partition.cacheIf(completePartition(), () -> tuple("min", function), () -> window().min(function));
    }

    @Override
    public <U> Optional<U> min(Function<? super T, ? extends U> function, Comparator<? super U> comparator) {
        return partition.cacheIf(completePartition(), () -> tuple("min", function, comparator), () -> window().min(function, comparator));
    }

    @Override
    public <U extends Comparable<? super U>> Optional<T> minBy(Function<? super T, ? extends U> function) {
        return partition.cacheIf(completePartition(), () -> tuple("minBy", function), () -> window().minBy(function));
    }

    @Override
    public <U> Optional<T> minBy(Function<? super T, ? extends U> function, Comparator<? super U> comparator) {
        return partition.cacheIf(completePartition(), () -> tuple("minBy", function, comparator), () -> window().minBy(function, comparator));
    }

    @Override
    public Optional<T> max() {
        return partition.cacheIf(completePartition(), "max", () -> window().max((Comparator) naturalOrder()));
    }

    @Override
    public Optional<T> max(Comparator<? super T> comparator) {
        return partition.cacheIf(completePartition(), () -> tuple("max", comparator), () -> window().max(comparator));
    }

    @Override
    public <U extends Comparable<? super U>> Optional<U> max(Function<? super T, ? extends U> function) {
        return partition.cacheIf(completePartition(), () -> tuple("max", function), () -> window().max(function));
    }

    @Override
    public <U> Optional<U> max(Function<? super T, ? extends U> function, Comparator<? super U> comparator) {
        return partition.cacheIf(completePartition(), () -> tuple("max", function, comparator), () -> window().max(function, comparator));
    }

    @Override
    public <U extends Comparable<? super U>> Optional<T> maxBy(Function<? super T, ? extends U> function) {
        return partition.cacheIf(completePartition(), () -> tuple("maxBy", function), () -> window().maxBy(function));
    }

    @Override
    public <U> Optional<T> maxBy(Function<? super T, ? extends U> function, Comparator<? super U> comparator) {
        return partition.cacheIf(completePartition(), () -> tuple("maxBy", function, comparator), () -> window().maxBy(function, comparator));
    }

    @Override
    public Optional<T> median() {
        return partition.cacheIf(completePartition(), "median", () -> window().median((Comparator) naturalOrder()));
    }

    @Override
    public Optional<T> median(Comparator<? super T> comparator) {
        return partition.cacheIf(completePartition(), () -> tuple("median", comparator), () -> window().median(comparator));
    }

    @Override
    public <U extends Comparable<? super U>> Optional<T> medianBy(Function<? super T, ? extends U> function) {
        return partition.cacheIf(completePartition(), () -> tuple("medianBy", function), () -> window().medianBy(function));
    }

    @Override
    public <U> Optional<T> medianBy(Function<? super T, ? extends U> function, Comparator<? super U> comparator) {
        return partition.cacheIf(completePartition(), () -> tuple("medianBy", function, comparator), () -> window().medianBy(function, comparator));
    }

    @Override
    public Optional<T> percentile(double percentile) {
        return partition.cacheIf(completePartition(), () -> tuple("percentile", percentile), () -> window().percentile(percentile, (Comparator) naturalOrder()));
    }

    @Override
    public Optional<T> percentile(double percentile, Comparator<? super T> comparator) {
        return partition.cacheIf(completePartition(), () -> tuple("percentile", percentile, comparator), () -> window().percentile(percentile, comparator));
    }

    @Override
    public <U extends Comparable<? super U>> Optional<T> percentileBy(double percentile, Function<? super T, ? extends U> function) {
        return partition.cacheIf(completePartition(), () -> tuple("percentileBy", percentile, function), () -> window().percentileBy(percentile, function));
    }

    @Override
    public <U> Optional<T> percentileBy(double percentile, Function<? super T, ? extends U> function, Comparator<? super U> comparator) {
        return partition.cacheIf(completePartition(), () -> tuple("percentileBy", percentile, function, comparator), () -> window().percentileBy(percentile, function, comparator));
    }

    @Override
    public Optional<T> mode() {
        return partition.cacheIf(completePartition(), "mode", () -> window().mode());
    }

    @Override
    public boolean allMatch(Predicate<? super T> predicate) {
        return partition.cacheIf(completePartition(), () -> tuple("allMatch", predicate), () -> window().allMatch(predicate));
    }

    @Override
    public boolean anyMatch(Predicate<? super T> predicate) {
        return partition.cacheIf(completePartition(), () -> tuple("anyMatch", predicate), () -> window().anyMatch(predicate));
    }

    @Override
    public boolean noneMatch(Predicate<? super T> predicate) {
        return partition.cacheIf(completePartition(), () -> tuple("noneMatch", predicate), () -> window().noneMatch(predicate));
    }

    @Override
    public <R, A> R collect(Collector<? super T, A, R> collector) {
        return partition.cacheIf(completePartition(), () -> tuple("collect", collector), () -> window().collect(collector));
    }

    @Override
    public List<T> toList() {
        return partition.cacheIf(completePartition(), "toList", () -> window().toList());
    }
    
    @Override
    public <L extends List<T>> L toList(Supplier<L> factory) {
        return partition.cacheIf(completePartition(), () -> tuple("toList", factory), () -> window().toList(factory));
    }

    @Override
    public Set<T> toSet() {
        return partition.cacheIf(completePartition(), "toSet", () -> window().toSet());
    }

    @Override
    public <S extends Set<T>> S toSet(Supplier<S> factory) {
        return partition.cacheIf(completePartition(), () -> tuple("toSet", factory), () -> window().toSet(factory));
    }

    @Override
    public <C extends Collection<T>> C toCollection(Supplier<C> factory) {
        return partition.cacheIf(completePartition(), () -> tuple("toCollection", factory), () -> window().toCollection(factory));
    }
    
    @Override
    public <K, V> Map<K, V> toMap(Function<? super T, ? extends K> keyMapper, Function<? super T, ? extends V> valueMapper) {
        return partition.cacheIf(completePartition(), () -> tuple("toMap", keyMapper, valueMapper), () -> window().toMap(keyMapper, valueMapper));
    }

    @Override
    public String toString() {
        return partition.cacheIf(completePartition(), "toString", () -> window().toString());
    }
    
    @Override
    public String toString(CharSequence delimiter) {
        return partition.cacheIf(completePartition(), () -> tuple("toString", delimiter), () -> Seq.toString(window(), delimiter));
    }

    @Override
    public String toString(CharSequence delimiter, CharSequence prefix, CharSequence suffix) {
        return partition.cacheIf(completePartition(), () -> tuple("toString", delimiter, prefix, suffix), () -> window().map(Objects::toString).collect(Collectors.joining(delimiter, prefix, suffix)));
    }
}