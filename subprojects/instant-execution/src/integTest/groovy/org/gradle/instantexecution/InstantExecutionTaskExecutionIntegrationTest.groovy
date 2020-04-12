/*
 * Copyright 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.instantexecution

import org.gradle.test.fixtures.server.http.BlockingHttpServer
import org.junit.Rule

class InstantExecutionTaskExecutionIntegrationTest extends AbstractInstantExecutionIntegrationTest {

    @Rule
    BlockingHttpServer server = new BlockingHttpServer()

    def "honors task up-to-date spec"() {
        buildFile << """
            abstract class TaskWithComplexInputs extends DefaultTask {
                @OutputFile
                abstract RegularFileProperty getOutputFile()

                TaskWithComplexInputs() {
                    def result = name == "never"
                    outputs.upToDateWhen { !result }
                }

                @TaskAction
                def go() {
                    outputFile.get().asFile.text = "some-derived-value"
                }
            }

            task never(type: TaskWithComplexInputs) {
                outputFile = layout.buildDirectory.file("never.txt")
            }
            task always(type: TaskWithComplexInputs) {
                outputFile = layout.buildDirectory.file("always.txt")
            }
        """

        when:
        instantRun("never", "always")
        instantRun("never", "always")

        then:
        result.assertTaskSkipped(":always")
        result.assertTasksNotSkipped(":never")
    }

    def "honors task finalizedBy"() {

        def instant = newInstantExecutionFixture()
        server.start()

        given:
        buildFile << """
            task a {
                doLast { ${server.callFromBuild("a")} }
            }
            task b {
                finalizedBy a
                doLast {
                    Thread.sleep(500) // In order to exhibit the problem without flakiness
                    ${server.callFromBuild("b")}
                }
            }
        """

        when:
        server.expectConcurrent('b')
        server.expectConcurrent('a')
        instantRun 'b', '--parallel'

        then:
        instant.assertStateStored()

        when:
        server.expectConcurrent('b')
        server.expectConcurrent('a')
        instantRun 'b'

        then:
        instant.assertStateLoaded()

        cleanup:
        server.stop()
    }

    def "honors task mustRunAfter"() {

        def instant = newInstantExecutionFixture()
        server.start()

        given:
        buildFile << """
            task a {
                doLast { ${server.callFromBuild("a")} }
            }
            task b {
                doLast { ${server.callFromBuild("b")} }
            }
            task c(dependsOn: ['a', 'b']) {
                doLast { ${server.callFromBuild("c")} }
            }
            task d {
                doLast { ${server.callFromBuild("d")} }
            }
            c.mustRunAfter d
        """

        when:
        server.expectConcurrent("d")
        server.expectConcurrent("a")
        server.expectConcurrent("b")
        server.expectConcurrent("c")
        instantRun 'c', 'd'

        then:
        instant.assertStateStored()

        when:
        server.expectConcurrent("d", "a", "b")
        server.expectConcurrent("c")
        instantRun 'c', 'd'

        then:
        instant.assertStateLoaded()

        cleanup:
        server.stop()
    }

    def "honors task shouldRunAfter"() {

        def instant = newInstantExecutionFixture()
        server.start()

        given:
        buildFile << """
            task a() {
                dependsOn 'b'
                doLast { ${server.callFromBuild("a")} }
            }
            task b() {
                shouldRunAfter 'c'
                doLast { ${server.callFromBuild("b")} }
            }
            task c() {
                doLast {
                    Thread.sleep(500) // In order to exhibit the problem without flakiness
                    ${server.callFromBuild("c")}
                }
            }
            task d() {
                dependsOn 'c'
                doLast { ${server.callFromBuild("d")} }
            }
        """

        when:
        server.expectConcurrent('c')
        server.expectConcurrent('b')
        server.expectConcurrent('a')
        server.expectConcurrent('d')
        instantRun 'a', 'd'

        then:
        instant.assertStateStored()

        when:
        server.expectConcurrent("c")
        server.expectConcurrent("b")
        server.expectConcurrent("a", "d")
        instantRun 'a', 'd'

        then:
        instant.assertStateLoaded()

        cleanup:
        server.stop()
    }
}
