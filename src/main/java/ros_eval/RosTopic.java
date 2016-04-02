package ros_eval;

import org.rhea_core.Stream;
import org.rhea_core.annotations.PlacementConstraint;
import org.rhea_core.internal.Notification;
import org.rhea_core.internal.output.Output;
import org.rhea_core.io.AbstractTopic;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.ros.node.ConnectedNode;
import std_msgs.ByteMultiArray;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * ROS implementation of {@link AbstractTopic}.
 * @author Orestis Melkonian
 */
@PlacementConstraint(constraint = "ros")
public class RosTopic<T> extends AbstractTopic<T, ByteMultiArray, ConnectedNode> {

    static final String type = ByteMultiArray._TYPE;

    org.ros.node.topic.Publisher<ByteMultiArray> rosPublisher;
    org.ros.node.topic.Subscriber<ByteMultiArray> rosSubscriber;

    /**
     * Constructor
     * @param name the name of this RosTopic
     */
    public RosTopic(String name) {
        super(name, new RosSerializer());
    }

    public void setClient(ConnectedNode client) {
        rosPublisher = client.newPublisher(name, type);
        rosSubscriber = client.newSubscriber(name, type);
    }

    /**
     * Subscriber implementation
     */
    BlockQueue queue = new BlockQueue();

    @Override
    public void onSubscribe(Subscription s) {
        s.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(T t) {
        queue.block();

        Notification<T> notification = Notification.createOnNext(t);
        rosPublisher.publish(serializer.serialize(notification));
        if (Stream.DEBUG)
            System.out.println(name() + ": Send\t" + notification.getValue());

        queue.unblock();
    }

    @Override
    public void onError(Throwable t) {
        queue.block();

        Notification<T> notification = Notification.createOnError(t);
        rosPublisher.publish(serializer.serialize(notification));

        queue.unblock();
    }

    @Override
    public void onComplete() {
        queue.block();

        Notification<T> notification = Notification.createOnCompleted();
        rosPublisher.publish(serializer.serialize(notification));
        if (Stream.DEBUG)
            System.out.println(name() + ": Send\tComplete");

        queue.unblock();
    }

    /**
     * Publisher implementation
     */
    @Override
    public void subscribe(Subscriber<? super T> s) {
        rosSubscriber.addMessageListener(msg -> {
            Notification<T> notification = serializer.deserialize(msg);
            switch (notification.getKind()) {
                case OnNext:
                    if (Stream.DEBUG)
                        System.out.println(name() + ": Recv\t" + notification.getValue());
                    s.onNext(notification.getValue());
                    break;
                case OnError:
                    s.onError(notification.getThrowable());
                    break;
                case OnCompleted:
                    if (Stream.DEBUG)
                        System.out.println(name() + ": Recv\tComplete");
                    s.onComplete();
                    break;
                default:
            }
        });
    }

    private class BlockQueue {
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(1);
        long delay = 500;

        public void block() {
            if (Stream.DEBUG)
                System.out.println("[" + Thread.currentThread().getId() + "] Blocking");

            try {
                queue.put(new Object());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        public void unblock() {
            if (Stream.DEBUG)
                System.out.println("[" + Thread.currentThread().getId() + "] Unblocking");

            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            try {
                queue.take();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public RosTopic clone() {
        return new RosTopic(name);
    }

    public static List<RosTopic> extract(Stream stream, Output output) {
        List<RosTopic> topics = new ArrayList<>();

        for (AbstractTopic topic : AbstractTopic.extractAll(stream, output))
            if (topic instanceof RosTopic)
                topics.add(((RosTopic) topic));

        return topics;
    }
}
