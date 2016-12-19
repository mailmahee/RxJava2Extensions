/*
 * Copyright 2016 David Karnok
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

package hu.akarnokd.rxjava2.basetypes;

import java.util.concurrent.atomic.*;

import org.reactivestreams.*;

import io.reactivex.Scheduler;
import io.reactivex.Scheduler.Worker;
import io.reactivex.disposables.Disposable;
import io.reactivex.internal.disposables.DisposableHelper;
import io.reactivex.internal.subscriptions.SubscriptionHelper;

/**
 * Subscribe to the upstream on the specified scheduler.
 *
 * @param <T> the value type
 */
final class SoloSubscribeOn<T> extends Solo<T> {

    final Solo<T> source;

    final Scheduler scheduler;

    SoloSubscribeOn(Solo<T> source, Scheduler scheduler) {
        this.source = source;
        this.scheduler = scheduler;
    }

    @Override
    protected void subscribeActual(Subscriber<? super T> s) {
        Worker worker = scheduler.createWorker();

        SubscribeOnSubscriber<T> parent = new SubscribeOnSubscriber<T>(s, worker, source);
        s.onSubscribe(parent);

        DisposableHelper.replace(parent.task, worker.schedule(parent));
    }

    static final class SubscribeOnSubscriber<T>
    extends AtomicReference<Subscription>
    implements Subscriber<T>, Runnable, Subscription {

        private static final long serialVersionUID = 2047863608816341143L;

        final Subscriber<? super T> actual;

        final Worker worker;

        final AtomicReference<Disposable> task;

        final Publisher<T> source;

        final AtomicBoolean requested;

        SubscribeOnSubscriber(Subscriber<? super T> actual, Worker worker, Publisher<T> source) {
            this.actual = actual;
            this.worker = worker;
            this.source = source;
            this.task = new AtomicReference<Disposable>();
            this.requested = new AtomicBoolean();
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.setOnce(this, s)) {
                if (requested.getAndSet(false)) {
                    scheduleRequest();
                }
            }
        }

        @Override
        public void onNext(T t) {
            actual.onNext(t);
        }

        @Override
        public void onError(Throwable t) {
            actual.onError(t);
            worker.dispose();
        }

        @Override
        public void onComplete() {
            actual.onComplete();
            worker.dispose();
        }

        @Override
        public void run() {
            source.subscribe(this);
        }

        void scheduleRequest() {
            worker.schedule(new Runnable() {
                @Override
                public void run() {
                    get().request(Long.MAX_VALUE);
                }
            });
        }

        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                Subscription s = get();
                if (s != null) {
                    scheduleRequest();
                } else {
                    requested.set(true);
                    s = get();
                    if (s != null) {
                        if (requested.getAndSet(false)) {
                            scheduleRequest();
                        }
                    }
                }
            }
        }

        @Override
        public void cancel() {
            SubscriptionHelper.cancel(this);
            DisposableHelper.dispose(task);
            worker.dispose();
        }
    }
}
