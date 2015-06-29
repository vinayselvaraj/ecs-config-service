# -*- mode: ruby -*-
# vi: set ft=ruby :

Vagrant.configure(2) do |config|
  config.vm.box = "chef/centos-7.0"
  #config.vm.network "public_network", bridge: 'en0: Wi-Fi (AirPort)'

  config.vm.provider "virtualbox" do |vb|
     vb.memory = "1024"
  end

  # Enable provisioning with a shell script. Additional provisioners such as
  # Puppet, Chef, Ansible, Salt, and Docker are also available. Please see the
  # documentation for more information about their specific syntax and use.
  config.vm.provision "shell", inline: <<-SHELL
   yum -y update
   yum -y install docker
  SHELL
end
