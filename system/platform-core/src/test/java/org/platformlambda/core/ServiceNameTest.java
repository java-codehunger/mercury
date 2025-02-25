/*

    Copyright 2018-2023 Accenture Technology

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

 */

package org.platformlambda.core;

import org.junit.Test;

import org.junit.Assert;
import org.platformlambda.core.util.Utility;

public class ServiceNameTest {

    private static final Utility util = Utility.getInstance();

    @Test
    public void filterName() {
        String valid = "hello.world";
        String invalid = "hello.wor?ld";
        String dotted = "..."+invalid;
        String filtered1 = util.filteredServiceName(invalid);
        Assert.assertEquals(valid, filtered1);
        String filtered2 = util.filteredServiceName(dotted);
        Assert.assertEquals(valid, filtered2);
    }

    @Test
    public void validName() {
        String windowsMetafile = "thumbs.db";
        String windowsExt = "hello.com";
        String valid = "com.hello";
        Assert.assertTrue(util.reservedFilename(windowsMetafile));
        Assert.assertTrue(util.reservedExtension(windowsExt));
        Assert.assertFalse(util.reservedExtension(valid));
    }

}
