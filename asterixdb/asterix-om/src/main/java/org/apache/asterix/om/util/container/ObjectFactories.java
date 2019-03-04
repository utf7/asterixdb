/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.asterix.om.util.container;

import org.apache.asterix.builders.AbvsBuilderFactory;
import org.apache.asterix.om.types.ATypeTag;
import org.apache.hyracks.data.std.api.IMutableValueStorage;
import org.apache.hyracks.data.std.api.IPointable;
import org.apache.hyracks.data.std.primitive.VoidPointable;

// TODO(ali): look for all classes creating factories and extract them to here
public class ObjectFactories {

    private ObjectFactories() {
    }

    public static final IObjectFactory<IPointable, Void> VOID_FACTORY = (type) -> new VoidPointable();
    // TODO(ali): use lambda for the storage, too
    public static final IObjectFactory<IMutableValueStorage, ATypeTag> STORAGE_FACTORY = new AbvsBuilderFactory();
}
