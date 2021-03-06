package com.example.common_demo.seckill;


import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * 模拟秒杀场景，把用户请求放入队列进行合并，处理完成后通知用户
 */
public class SeckillDemo {
    /**
     * 启动10个线程
     * 库存6个
     * 生成一个合并队列
     * 每个用户能拿到自己的请求响应
     */
    public static void main(String[] args) throws InterruptedException {
        ExecutorService executorService = Executors.newCachedThreadPool();
        SeckillDemo killDemo = new SeckillDemo();
        killDemo.mergeJob();
        Thread.sleep(2000);

        List<Future<Result>> futureList = new ArrayList<>();
        CountDownLatch countDownLatch = new CountDownLatch(10);
        for (int i = 0; i < 10; i++) {
            final Long orderId = i + 100L;
            final Long userId = Long.valueOf(i);
            Future<Result> future = executorService.submit(() -> {
                countDownLatch.countDown();
                countDownLatch.await (1000, TimeUnit.SECONDS);
                return killDemo.operate(new UserRequest(orderId, userId, 1));
            });
            futureList.add(future);
        }

        futureList.forEach(future -> {
            try {
                Result result = future.get(300, TimeUnit.MILLISECONDS);
                System.out.println(Thread.currentThread().getName() + ":客户端请求响应:" + result);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    // 模拟数据库行
    private Integer stock = 10;

    private BlockingQueue<RequestPromise> queue = new LinkedBlockingQueue<>(10);
    /**
     * 用户库存扣减
     * @param userRequest
     * @return
     */
    public Result operate(UserRequest userRequest) throws InterruptedException {
        // TODO 阈值判断
        // TODO 队列的创建

        /**
         * 小马哥
         * RequestPromise requestPromise 这个对象在方法内部，当多线程访问时，每个线程看到的 requestPromise 对象是对立的，
         * 在偏离锁的作用下， synchromized(requestPromise) 是没有互斥执行效果的，
         * 且 requestPromise.wait(200) 调用并不能做到阻塞其他线程作用，即 requestPromise 对象不是共享对象。
         */

        /**
         * requestPromise这个对象是存在队列里面的，队列是全局共享变量，在另外一个异步线程去操作
         * 并且wait方法的调用也是必须在同步代码块
         */

        /**
         * 最后还是有并发问题，如果不加synchronized的话。
         * queue.offer()入队列是在用户请求的时候，队列的消费是在一个异步线程：mergeJob()
         * 用户的请求线程是在异步线程轮训之后才进行提交。在高并发的情况下有可能刚入队列，然后异步线程就直接消费，然后就直接消费然后notify
         * 这样的话 它的notify比wait方法更先执行，这样的话每个线程都要等待200毫秒： requestPromise.wait(200);
         * 所以入队列的代码也要加入同步
         *
         *
         */

        RequestPromise requestPromise = new RequestPromise(userRequest);
        //所以这个synchronized同步只是为了使用wait，wait/notify都是在同步代码块里才能调用？
        synchronized (requestPromise) {
            boolean enqueueSuccess = queue.offer(requestPromise, 100, TimeUnit.MILLISECONDS);
            if (! enqueueSuccess) {
                return new Result(false, "系统繁忙");
            }
            try {
                //作者原话：开始认为等待超时会抛出中断异常即InterruptedException ，等待结束后就会直接往下走，并不会抛出异常
                //怎么区分是被notify还是等待超时
                requestPromise.wait(200);
                //不管库存够还是不够都会setresult
                if (requestPromise.getResult() == null) {
                    return new Result(false, "等待超时");
                }
            } catch (InterruptedException e) {
                return new Result(false, "被中断");
            }
        }
        return requestPromise.getResult();
    }

    public void mergeJob() {
        new Thread(() -> {
            List<RequestPromise> list = new ArrayList<>();
            while (true) {
                if (queue.isEmpty()) {
                    try {
                        Thread.sleep(10);
                        //等待完了继续轮训 
                        continue;
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                /**
                 * peek()返回队列的头元素
                 * 下面代码的意思是如果有元素就一直取，
                 * 这一步应该要控制循环次数或时间，否则可能会无限合并
                 */
//                while(queue.peek()!= null){
//                    list.add(queue.poll());
//                }

                //从队列中取，然后放入list
                int batchSize = queue.size();
                for (int i = 0; i < batchSize; i++) {
                    list.add(queue.poll());
                }

                System.out.println(Thread.currentThread().getName() + ":合并扣减库存:" + list);

                int sum = list.stream().mapToInt(e -> e.getUserRequest().getCount()).sum();
                // 两种情况，1、库存充足，所有用户请求的库存数量小于真实库存数量的话
                if (sum <= stock) {
                    stock -= sum;
                    // notify user
                    list.forEach(requestPromise -> {
                        requestPromise.setResult(new Result(true, "ok"));
                        synchronized (requestPromise) {
                            requestPromise.notify();
                        }
                    });
                    list.clear();
                    continue;
                }
                //库存不足的情况，这里不应该是先满足请求数量大的么？没有排序的操作
                for (RequestPromise requestPromise : list) {
                    int count = requestPromise.getUserRequest().getCount();
                    if (count <= stock) {
                        stock -= count;
                        requestPromise.setResult(new Result(true, "ok"));
                    } else {
                        requestPromise.setResult(new Result(false, "库存不足"));
                    }
                    synchronized (requestPromise) {
                        requestPromise.notify();
                    }
                }
                list.clear();
            }
        }, "mergeThread").start();
    }
}
class RequestPromise {
    private UserRequest userRequest;
    private Result result;

    public RequestPromise(UserRequest userRequest) {
        this.userRequest = userRequest;
    }

    public RequestPromise(UserRequest userRequest, Result result) {
        this.userRequest = userRequest;
        this.result = result;
    }

    public UserRequest getUserRequest() {
        return userRequest;
    }

    public void setUserRequest(UserRequest userRequest) {
        this.userRequest = userRequest;
    }

    public Result getResult() {
        return result;
    }

    public void setResult(Result result) {
        this.result = result;
    }

    @Override
    public String toString() {
        return "RequestPromise{" +
                "userRequest=" + userRequest +
                ", result=" + result +
                '}';
    }
}
class Result {
    private Boolean success;
    private String msg;

    public Result(boolean success, String msg) {
        this.success = success;
        this.msg = msg;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    @Override
    public String toString() {
        return "Result{" +
                "success=" + success +
                ", msg='" + msg + '\'' +
                '}';
    }
}
class UserRequest {
    private Long orderId;
    private Long userId;
    private Integer count;

    public UserRequest(Long orderId, Long userId, Integer count) {
        this.orderId = orderId;
        this.userId = userId;
        this.count = count;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getCount() {
        return count;
    }

    public void setCount(Integer count) {
        this.count = count;
    }

    @Override
    public String toString() {
        return "UserRequest{" +
                "orderId=" + orderId +
                ", userId=" + userId +
                ", count=" + count +
                '}';
    }
}