package com.example.aiagent.memory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

/**
 * 用 Kryo 把消息列表序列化成二进制，存进 Redis。
 *
 * <p>Spring AI 的 Message 没有无参构造函数，所以必须挂 StdInstantiatorStrategy
 * （走 Objenesis 绕过构造器），外面再包一层 DefaultInstantiatorStrategy 兜底。
 */
@Component
public class KryoMessageSerializer {

    public byte[] serialize(List<Message> messages) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                Output output = new Output(outputStream)) {
            createKryo().writeObject(output, new ArrayList<>(messages));
            output.flush();
            return outputStream.toByteArray();
        }
        catch (IOException e) {
            throw new IllegalStateException("Kryo 序列化对话记忆失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    public List<Message> deserialize(byte[] data) {
        try (Input input = new Input(new ByteArrayInputStream(data))) {
            return createKryo().readObject(input, ArrayList.class);
        }
    }

    /**
     * Kryo 实例不是线程安全的，所以每次操作新建一个。
     * 单轮对话只调用一两次，开销可以接受；真嫌慢可以换成 kryo.util.Pool 池化。
     */
    private Kryo createKryo() {
        Kryo kryo = new Kryo();
        kryo.setRegistrationRequired(false);
        kryo.setInstantiatorStrategy(new DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));
        return kryo;
    }
}
