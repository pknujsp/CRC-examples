import java.security.MessageDigest
import kotlin.random.Random
import kotlin.text.Charsets.UTF_8

private val ERROR_PERCENTAGE = 20

val stringLength = 100 // 문자열 길이
val numOfCases = 1000 // 만들 테스트 케이스 개수

private val MSG_DIGEST = MessageDigest.getInstance("SHA-256")

abstract class BaseCrc(val generator: String, val initv: String) {

    // 인코딩과 디코딩을 위한 계산 시 Dividend 뒤에 추가되는 비트
    private val extraZeroBitsOnDividend = IntArray(generator.length - 1).joinToString("")
    private val zeroBits = IntArray(generator.length).joinToString("")

    data class DecodeResult(
        val errorCorrupted: Boolean,
        val codeword: String,
    )

    fun interface ElapsedTimeCallback {
        fun onResult(elapsedTime: Long)
    }

    /**
     * 입력 - dataword
     * 출력 - codeword
     * 동작
     *
     * 1. dataword뒤에 generator길이 - 1만큼 0을 추가한다.
     * 2. 추가한 데이터를 dividend로 하고 generator로 나눈다.
     * 3. 나머지를 syndrome으로 추가하고 codeword로 만들어 반환한다.
     *
     * 일반적인 이진수 나눗셈과 다르다.
     *
     * 나눠야 하는 값의 가장 왼쪽의 비트가 몫에 1을 추가하고, 0이라면 몫을 0으로 한다.
     **/
    fun encode(dataWord: String, elapsedTimeCallback: ElapsedTimeCallback?): String =
        dataWord.let { dividend ->
            val startTime = System.nanoTime()
            divide(initv + dividend + extraZeroBitsOnDividend).let { remainder ->
                elapsedTimeCallback?.apply {
                    this.onResult(System.nanoTime() - startTime)
                }
                dataWord + remainder
            }
        }

    /**
     * return - Corruption status
     *
     * codeWord % generator 가 0이 아니면 오류가 발생한 것이다.
     */
    fun decode(codeWord: String, elapsedTimeCallback: ElapsedTimeCallback?): DecodeResult =
        codeWord.let { dividend ->
            val startTime = System.nanoTime()

            divide(dividend).let {
                elapsedTimeCallback?.apply {
                    this.onResult(System.nanoTime() - startTime)
                }
                //println(it)
                DecodeResult(it.contains('1', ignoreCase = false), dividend)
            }
        }


    private fun divide(data: String): String = data.let { dividend ->
        val remainder = dividend.toCharArray().toMutableList()
        val quotient = mutableListOf<Char>()
        val quotientMaxLength = dividend.length - extraZeroBitsOnDividend.length

        // 이진수 나눗셈 수행
        for (pos in 0 until quotientMaxLength) {
            remainder.first().also { q ->
                // 몫을 구한다.
                quotient.add(q)

                // divisor * 몫에 추가한 비트 결과
                (if (q == '0') zeroBits else generator).also { divisorXq ->
                    // 나머지에 추가할 비트
                    val remainderXorDivisorXq = divisorXq.mapIndexed { index, c ->
                        if ((remainder[index].code xor c.code) == 1) '1' else '0'
                    }.subList(1, divisorXq.length)

                    // 나머지를 갱신한다.
                    remainder.clear()
                    remainder.addAll(remainderXorDivisorXq)
                }

                if ((pos + generator.length) < dividend.length) {
                    remainder.add(
                        dividend[pos + generator.length]
                    )
                } else
                    remainder.add('0')
            }
        }

        remainder.subList(0, generator.length - 1).joinToString("")
    }

}

class BinaryGenerator {
    companion object {

        /**
         * 입력 : 문자열
         *
         * 출력 : 이진화된 문자열
         *
         * 동작
         * 1. 문자열의 각 문자를 아스키 코드로 바꿈
         * 2. 아스키 코드를 이진수로 바꿈
         * 3. 바뀐 이진수를 순서대로 리스트화
         */
        fun toBinary(data: String): BinaryData = BinaryData(data.toCharArray().joinToString("") {
            it.code.toString(radix = 2).padStart(8, '0')
        }, data)
    }

    /**
     * 이진수
     *
     * 원본 char
     */
    data class BinaryData(val binary: String, val originalData: String)
}

class Crc6 : BaseCrc(generator = "1000011", initv = "000000")

class Crc16 : BaseCrc(generator = "10001000000100001", initv = "1111111111111111")

data class TestData(
    var data: String,
)

// 오류가 있으나 검출되지 않은 데이터
data class NotCorruptedData(
    val dataWord: String,
    val originalData: String
)


/**
 * 10% 확률로 오류 포함
 */
fun testCrc(crc: BaseCrc, testCases: List<TestData>): List<Pair<Long, Long>> {
// 오류 탐지 성공 횟수
    var successfulErrorDetectionCount = 0
// 오류 탐지 실패 횟수
    var failedErrorDetectionCount = 0

    var totalRealErrorCount = 0

    var totalEncodingTime = 0L
    var totalDecodingTime = 0L
    var totalDataCount = 0

// 100바이트 마다 걸린 로직 수행 시간
    val timeList = mutableListOf<Pair<Long, Long>>()
    val notCorruptedDataList = mutableListOf<NotCorruptedData>()

    testCases.map { testData ->
        BinaryGenerator.toBinary(testData.data).also { binaryData ->
            totalDataCount += 100

            crc.encode(binaryData.binary) { encodingTime ->
// 인코딩 소요 시간
                totalEncodingTime += encodingTime
            }.also { codeWord ->
                val containsError = Random.nextFloat() <= ERROR_PERCENTAGE * 0.01

                // 오류 발생 확률에 따라 오류를 codeword에 만든다.
                (if (containsError) createError(codeWord) else codeWord).let { finalCodeWord ->

                    // 최종적으로 사용될 codeword
                    crc.decode(finalCodeWord) { decodingTime ->
                        // 디코딩 소요 시간
                        totalDecodingTime += decodingTime
                    }.also { decodeResult ->
                        // 오류 검출 확인 여부 파악
                        if (containsError) {
                            totalRealErrorCount++

                            if (decodeResult.errorCorrupted) {
                                successfulErrorDetectionCount++
                            } else {
                                failedErrorDetectionCount++
                                // 검증 실패한 데이터를 기록한다.
                                notCorruptedDataList.add(
                                    NotCorruptedData(
                                        binaryData.binary,
                                        binaryData.originalData
                                    )
                                )
                            }
                        }
                    }
                }


            }


        }

        timeList.add(
            Pair(totalEncodingTime, totalDecodingTime)
        )
    }

    timeList.mapIndexed { index, pair ->
        // CRC 계산과 오류검증시간
        println("${(index) * 100} ~ ${(index + 1) * 100} 사이의 바이트 -> CRC계산 소요시간 : ${pair.first.toFloat() / 1000000f}ms  오류검증 소요시간 : ${pair.second.toFloat() / 1000000f}ms")
    }


    notCorruptedDataList.map {
        println("오류가 있으나 검출되지 않은 데이터 -> Dataword : ${it.dataWord}, 원본 문자 : ${it.originalData}")
    }

    println("총 데이터 개수(크기) : $totalDataCount Bytes")
    println("실제 오류의 개수 : $totalRealErrorCount")
    println("전체 데이터 중 오류의 비율 : ${totalRealErrorCount.toFloat() / numOfCases.toFloat() * 100f}%")
    println("오류 탐지 개수 : $successfulErrorDetectionCount")
    println("오류 실패 개수 : $failedErrorDetectionCount")
    println("오류 탐지 성공률 : ${successfulErrorDetectionCount.toFloat() / totalRealErrorCount.toFloat() * 100f}%")

    println("--------------------------------------------------------------------------------------------------------------")

    return timeList
}


/**
 * 100,000 바이트 데이터를 랜덤으로 만들어서 100바이트씩 테스트한다.
 *
 */
fun makeTestCase(): List<TestData> {


    /*
    UTF-8에서
    숫자와 영어 1바이트
     */

    return 1.rangeTo(numOfCases).map {
        TestData(
            getHash(System.nanoTime().toString(), stringLength)
        )
    }.toList()
}

/**
 * 랜덤하게 하나의 비트를 바꾼다.
 */
fun createError(codeWord: String): String =
    codeWord.toCharArray().let { binary ->
        Random.nextInt(0, 800).let { randomIdx ->
            binary[randomIdx] = if (binary[randomIdx] == '1') '0' else '1'
            binary.joinToString("")
        }.toString()
    }


fun getHash(input: String, length: Int): String =
    MSG_DIGEST.digest(input.toByteArray(UTF_8)).joinToString("") {
        "%02x".format(it)
    }.repeat(2).substring(0, length)


val cases = makeTestCase()
println("---------전체 데이터 중 오류 비율 목표 : $ERROR_PERCENTAGE%----------------------")

val result6: List<Pair<Long, Long>> = testCrc(Crc6(), cases)
println("----------- CRC-6-ITU -------------------------------------------------------------")

println("----------- CRC-16-CCITT ----------------------------------------------------------")
val result16: List<Pair<Long, Long>> = testCrc(Crc16(), cases)

%useLatestDescriptors
%use lets-plot
%use krangl
LetsPlot.getInfo()

val data = mapOf(
    "bytes" to List(numOfCases) { it * 100 } + List(numOfCases) { it * 100 } + List(numOfCases) { it * 100 } + List(
        numOfCases
    ) { it * 100 },
    "ms" to result6.map { it.first.toFloat() / 1000000f }.toList() + result6.map { it.second.toFloat() / 1000000f }
        .toList() +
            result16.map { it.first.toFloat() / 1000000f }.toList() + result16.map { it.second.toFloat() / 1000000f }
        .toList(),
    "types" to List(numOfCases) { "CRC-6 CRC연산" } + List(numOfCases) { "CRC-6 오류연산" } + List(numOfCases) { "CRC-16 CRC연산" } + List(
        numOfCases
    ) { "CRC-16 오류연산" }
)

var p =
    letsPlot(data) { x = "bytes"; y = "ms"; color = "types" } + geomPoint(
        alpha = 0.7, size = 0.1,
        tooltips = layerTooltips()
            .title("@types")
            .line("bytes|@bytes")
            .line("ms|@ms")

    ) { color = "types" }
p += geomSmooth(method = "loess", size = 1.5) { color = asDiscrete("types") }
p += ggsize(1000, 700)
p.show()
