package com.work.order.service;

import com.alibaba.fastjson.JSON;
import com.work.order.model.Order;
import com.work.order.repository.OrderDAO;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.messaging.Message;

import javax.annotation.Resource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @program: springcloud-nacos-rocketmq-tx
 * @description: ${description}
 **/
@RocketMQTransactionListener
@Slf4j
public class TransactionListenerImpl implements RocketMQLocalTransactionListener {

    @Resource
    private OrderDAO orderDAO;


    /**
     * 执行本地事务
     *
     * @param msg
     * @param arg
     * @return
     */
    @SneakyThrows
    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        String transId = (String) msg.getHeaders().get(RocketMQHeaders.TRANSACTION_ID);
        log.info("#### executeLocalTransaction is executed, msgTransactionId={} ",
                transId);
        String order= new String((byte[])msg.getPayload(),"UTF-8");
        Order orderBean = JSON.parseObject(order, Order.class);
        //开始执行本地事务
        /**
         * 1、commit 成功
         * 2、Rollback 失败
         */
        try {
            int insert = orderDAO.insert(orderBean);
            //本地事务end========
            if (insert == 1) {
                return RocketMQLocalTransactionState.COMMIT;
            }
            //2、回滚消息，Broker端会删除半消息
            if (insert == 0) {
                return RocketMQLocalTransactionState.ROLLBACK;
            }
        } catch (Exception e) {
            //3、Broker端会进行回查消息
            return RocketMQLocalTransactionState.UNKNOWN;
        }
        return RocketMQLocalTransactionState.UNKNOWN;
    }
    /**
     * 数组转对象
     * @param bytes
     * @return
     */
    public Object toObject (byte[] bytes) {
        Object obj = null;
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream (bytes);
            ObjectInputStream ois = new ObjectInputStream (bis);
            obj = ois.readObject();
            ois.close();
            bis.close();
        } catch (IOException ex) {
            ex.printStackTrace();
        } catch (ClassNotFoundException ex) {
            ex.printStackTrace();
        }
        return obj;
    }

    /**
     * 检查本地事务
     *
     * @param msg
     * @return
     */
    @SneakyThrows
    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        String transId = (String) msg.getHeaders().get(RocketMQHeaders.TRANSACTION_ID);
        RocketMQLocalTransactionState retState = RocketMQLocalTransactionState.COMMIT;
//        Order order = (Order) msg.getPayload();
        String order= new String((byte[])msg.getPayload(),"UTF-8");
        Order orderBean = JSON.parseObject(order, Order.class);
        Map<String, Object> columnMap = new HashMap<>();
        columnMap.put("order_no", orderBean.getOrderNo());
        List<Order> orders = orderDAO.selectByMap(columnMap);
        /**
         * 因为有种情况就是：上面本地事务执行成功了，但是return LocalTransactionState.COMMIT_MESSAG的时候
         * 服务挂了，那么最终 Brock还未收到消息的二次确定，还是个半消息 ，所以当重新启动的时候还是回调这个回调接口。
         * 如果不先查询上面本地事务的执行情况 直接在执行本地事务，那么就相当于成功执行了两次本地事务了。
         */
        if (null != orders) {
            retState = RocketMQLocalTransactionState.COMMIT;
        } else {
            retState = RocketMQLocalTransactionState.ROLLBACK;
        }
        log.info("------ !!! checkLocalTransaction is executed once," +
                        " msgTransactionId={}, TransactionState={} ",
                transId, retState);
        return retState;
    }
}
