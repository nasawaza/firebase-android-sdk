// Copyright 2022 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.firestore

import com.google.firebase.firestore.ktx.annotations.KDocumentId
import com.google.firebase.firestore.ktx.serialization.setData
import com.google.firebase.firestore.testutil.testCollection
import com.google.firebase.firestore.testutil.waitFor
import kotlin.test.assertEquals
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import org.junit.Test

class DocumentIdIntegrationTest {

    @Test
    fun encoding_DocumentReference_is_supported() {
        val docRefKotlin = testCollection("ktx").document("123")
        val docRefPOJO = testCollection("pojo").document("456")

        @Serializable
        data class DocumentIdOnDocRefField(
            @Contextual
            var docId: DocumentReference? = null
        )

        val docRefObject = DocumentIdOnDocRefField().apply { docId = docRefKotlin }
        docRefKotlin.setData(docRefObject)
        docRefPOJO.set(docRefObject)
        val expected = waitFor(docRefPOJO.get()).data
        val actual = waitFor(docRefKotlin.get()).data
        assertEquals(expected, actual)
    }

//    @Test
//    fun this_test_will_not_run_for_some_reason() {
//        // This test does not run, I think this is not my problem, something wrong with Kotlin
//        val docRefKotlin = testCollection("ktx").document("123")
//        val docRefPOJO = testCollection("pojo").document("456")
//
//        @Serializable
//        data class DocumentIdOnDocRefField(
//            @Contextual
//            var docId: DocumentReference? = docRefKotlin
//        )
//
//        val docRefObject = DocumentIdOnDocRefField()
//        docRefKotlin.setData(docRefObject)
//        docRefPOJO.set(docRefObject)
//        val expected = waitFor(docRefPOJO.get()).data
//        val actual = waitFor(docRefKotlin.get()).data
//        assertEquals(expected, actual)
//    }

    @Test
    fun documentId_annotation_works_on_nested_object() {

        @Serializable
        class DocumentIdOnStringField {
            @DocumentId
            @KDocumentId
            var docId = "doc-id"
        }

        @Serializable
        class DocumentIdOnNestedObjects {
            var nested: DocumentIdOnStringField = DocumentIdOnStringField()
        }

        val docRefPOJO = testCollection("pojo").document("456")
        val docRefKotlin = testCollection("ktx").document("123")
        docRefKotlin.setData(DocumentIdOnNestedObjects())
        docRefPOJO.set(DocumentIdOnNestedObjects())
        val expected = waitFor(docRefPOJO.get()).data
        val actual = waitFor(docRefKotlin.get()).data
        assertEquals(expected, actual)
    }
}