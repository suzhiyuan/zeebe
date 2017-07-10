/**
 * Copyright © 2017 camunda services GmbH (info@camunda.com)
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
package io.zeebe.transport.impl.selector;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

import org.agrona.LangUtil;
import org.agrona.nio.TransportPoller;

import io.zeebe.transport.impl.TransportChannel;

public class ReadTransportPoller extends TransportPoller
{
    protected final List<TransportChannel> channels = new ArrayList<>();

    protected final ToIntFunction<SelectionKey> processKeyFn = this::processKey;

    public int pollNow()
    {
        int workCount = 0;

        if (channels.size() <= ITERATION_THRESHOLD)
        {
            for (int i = 0; i < channels.size(); i++)
            {
                final TransportChannel channel = channels.get(i);
                workCount += channel.receive();
            }
        }
        else
        {
            if (selector.isOpen())
            {
                try
                {
                    selector.selectNow();
                    workCount = selectedKeySet.forEach(processKeyFn);
                }
                catch (IOException e)
                {
                    selectedKeySet.reset();
                    LangUtil.rethrowUnchecked(e);
                }
            }
        }

        return workCount;
    }

    protected int processKey(SelectionKey key)
    {
        int workCount = 0;

        if (key != null && key.isReadable())
        {
            final TransportChannel channel = (TransportChannel) key.attachment();
            workCount = channel.receive();
        }

        return workCount;
    }

    public void addChannel(TransportChannel channel)
    {
        try
        {
            channel.registerSelector(selector, SelectionKey.OP_READ);
            channels.add(channel);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public void removeChannel(TransportChannel channel)
    {
        channels.remove(channel);
    }

}
