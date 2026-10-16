package main

import (
	"os"
	"os/exec"
	"sync"
)

// Запуск ядра отдельным процессом.
//
// Обёртка нужна ровно затем, чтобы за процессом можно было следить из
// одного места: Wait вызывается единожды, его результат запоминается, и
// любой желающий может дождаться конца, не споря за право звать Wait.
type process struct {
	cmd  *exec.Cmd
	log  *os.File
	done chan struct{}
	once sync.Once
	err  error
}

func startProcess(binary, config, assets, logPath string) (*process, error) {
	cmd := exec.Command(binary, "run", "-c", config)
	cmd.Dir = assets
	// Ядро ищет geoip.dat и geosite.dat рядом с собой либо по этой
	// переменной: держим их рядом с самой программой.
	cmd.Env = append(os.Environ(), "XRAY_LOCATION_ASSET="+assets)
	hideWindow(cmd)

	logFile, err := os.Create(logPath)
	if err == nil {
		cmd.Stdout = logFile
		cmd.Stderr = logFile
	}

	if err := cmd.Start(); err != nil {
		if logFile != nil {
			_ = logFile.Close()
		}
		return nil, err
	}

	proc := &process{cmd: cmd, log: logFile, done: make(chan struct{})}
	go func() {
		proc.err = cmd.Wait()
		if proc.log != nil {
			_ = proc.log.Close()
		}
		close(proc.done)
	}()
	return proc, nil
}

// wait — дождаться конца процесса. Можно звать откуда угодно и сколько
// угодно раз.
func (p *process) wait() error {
	<-p.done
	return p.err
}

func (p *process) kill() {
	p.once.Do(func() {
		if p.cmd.Process != nil {
			_ = p.cmd.Process.Kill()
		}
	})
	<-p.done
}
