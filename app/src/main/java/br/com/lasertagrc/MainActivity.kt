package br.com.lasertagrc

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    data class BtItem(
        val name: String,
        val address: String
    ) {
        override fun toString(): String = "$name  [$address]"
    }

    companion object {
        private const val REQUEST_BT_CONNECT = 1001

        // UUID padrao do Serial Port Profile (SPP)
        private val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null

    private lateinit var spinnerRobot: Spinner
    private lateinit var txtStatus: TextView

    private var pairedItems: List<BtItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerRobot = findViewById(R.id.spinnerRobot)
        txtStatus = findViewById(R.id.txtStatus)

        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter

        setupButtons()

        if (bluetoothAdapter == null) {
            setStatus("Este aparelho Android nao possui Bluetooth.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                REQUEST_BT_CONNECT
            )
        } else {
            loadPairedDevices()
        }
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            connectSelectedRobot()
        }

        setupMomentaryButton(findViewById(R.id.btnForward), "F")
        setupMomentaryButton(findViewById(R.id.btnBackward), "B")
        setupMomentaryButton(findViewById(R.id.btnLeft), "L")
        setupMomentaryButton(findViewById(R.id.btnRight), "R")

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            sendCommand("S")
        }

        findViewById<Button>(R.id.btnFire).setOnClickListener {
            sendCommand("T")
        }

        findViewById<Button>(R.id.btnStatus).setOnClickListener {
            sendCommand("Q")
        }

        findViewById<Button>(R.id.btnReset).setOnClickListener {
            sendCommand("Z")
        }
    }

    private fun setupMomentaryButton(button: Button, command: String) {
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    sendCommand(command)
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    sendCommand("S")
                    true
                }

                else -> false
            }
        }
    }

    private fun hasBtConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun loadPairedDevices() {
        if (!hasBtConnectPermission()) return

        if (!bluetoothAdapter.isEnabled) {
            setStatus("Ative o Bluetooth do celular e pareie com RC-LASERTAG-1.")
            return
        }

        pairedItems = bluetoothAdapter.bondedDevices
            .map { device ->
                BtItem(
                    device.name ?: "Dispositivo sem nome",
                    device.address
                )
            }
            .sortedBy { it.name }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            pairedItems
        )

        spinnerRobot.adapter = adapter

        if (pairedItems.isEmpty()) {
            setStatus("Nenhum dispositivo pareado. Pareie o ESP32 nas configuracoes do Android.")
        } else {
            setStatus("Selecione RC-LASERTAG-X e toque em Conectar.")
        }
    }

    private fun connectSelectedRobot() {
        if (!hasBtConnectPermission()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestPermissions(
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    REQUEST_BT_CONNECT
                )
            }
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            setStatus("Bluetooth desligado. Ative-o nas configuracoes do Android.")
            return
        }

        if (pairedItems.isEmpty()) {
            loadPairedDevices()
            return
        }

        val position = spinnerRobot.selectedItemPosition
        if (position !in pairedItems.indices) {
            setStatus("Selecione um robo.")
            return
        }

        val item = pairedItems[position]
        setStatus("Conectando a ${item.name}...")

        thread {
            try {
                closeConnection()

                val device = bluetoothAdapter.getRemoteDevice(item.address)
                val newSocket =
                    device.createRfcommSocketToServiceRecord(SPP_UUID)

                newSocket.connect()

                socket = newSocket
                output = newSocket.outputStream

                runOnUiThread {
                    setStatus("Conectado a ${item.name}")
                }

                startReader(newSocket)

                // Solicita estado inicial
                sendCommand("Q")

            } catch (e: Exception) {
                runOnUiThread {
                    setStatus("Falha ao conectar: ${e.message ?: "erro desconhecido"}")
                }
                closeConnection()
            }
        }
    }

    private fun sendCommand(command: String) {
        val stream = output

        if (stream == null || socket?.isConnected != true) {
            setStatus("Robo nao conectado.")
            return
        }

        thread {
            try {
                stream.write("$command\n".toByteArray(Charsets.UTF_8))
                stream.flush()
            } catch (e: Exception) {
                runOnUiThread {
                    setStatus("Erro ao enviar comando: ${e.message}")
                }
            }
        }
    }

    private fun startReader(connectedSocket: BluetoothSocket) {
        thread {
            try {
                val reader =
                    BufferedReader(InputStreamReader(connectedSocket.inputStream))

                while (connectedSocket.isConnected) {
                    val line = reader.readLine() ?: break

                    runOnUiThread {
                        setStatus(line)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setStatus("Conexao encerrada.")
                }
            }
        }
    }

    private fun setStatus(text: String) {
        txtStatus.text = "Status: $text"
    }

    private fun closeConnection() {
        try {
            output?.close()
        } catch (_: Exception) {
        }

        try {
            socket?.close()
        } catch (_: Exception) {
        }

        output = null
        socket = null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_BT_CONNECT) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                loadPairedDevices()
            } else {
                setStatus("Permissao de Bluetooth negada.")
            }
        }
    }

    override fun onDestroy() {
        closeConnection()
        super.onDestroy()
    }
}
