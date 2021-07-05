# :link: Ligoj Azure VM plugin [![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.ligoj.plugin/plugin-vm-azure/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.ligoj.plugin/plugin-vm-azure) [![Download](https://api.bintray.com/packages/ligoj/maven-repo/plugin-vm-azure/images/download.svg) ](https://bintray.com/ligoj/maven-repo/plugin-vm-azure/_latestVersion)

[![Build Status](https://travis-ci.com/ligoj/plugin-vm-azure.svg?branch=master)](https://travis-ci.com/ligoj/plugin-vm-azure)
[![Build Status](https://circleci.com/gh/ligoj/plugin-vm-azure.svg?style=svg)](https://circleci.com/gh/ligoj/plugin-vm-azure)
[![Build Status](https://ci.appveyor.com/api/projects/status/80sqifivkdifpaxp/branch/master?svg=true)](https://ci.appveyor.com/project/ligoj/plugin-vm-azure/branch/master)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=org.ligoj.plugin%3Aplugin-vm-azure&metric=coverage)](https://sonarcloud.io/dashboard?id=org.ligoj.plugin%3Aplugin-vm-azure)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?metric=alert_status&project=org.ligoj.plugin:plugin-vm-azure)](https://sonarcloud.io/dashboard/index/org.ligoj.plugin:plugin-vm-azure)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/edaa5b4d7dc0405eb10302b4ec34fbec)](https://www.codacy.com/app/ligoj/plugin-vm-azure?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=ligoj/plugin-vm-azure&amp;utm_campaign=Badge_Grade)
[![CodeFactor](https://www.codefactor.io/repository/github/ligoj/plugin-vm-azure/badge)](https://www.codefactor.io/repository/github/ligoj/plugin-vm-azure)
[![License](http://img.shields.io/:license-mit-blue.svg)](http://fabdouglas.mit-license.org/)

[Ligoj](https://github.com/ligoj/ligoj) EC2 AWS plugin, and extending [VM plugin](https://github.com/ligoj/plugin-vm)
Provides the following features :
- Supported operations from the [VM plugin](https://github.com/ligoj/plugin-vm) : ON, OFF, REBOOT, RESTART. No suspend or resume.
- Use AWS secret and access key with AWS API 4.0

Dashboard features :
- Status of the VM, including the intermediate busy mode
- OS
- DNS and IP
- Disk size
- CPU
- RAM

# Susbcription parameters
* Tenant ID (Directory ID)
* Application ID (Id of application account of ligoj)
* Key (secret token of application account of ligoj)
* Subscription ID
* Resource group name
* VM (RM) name

## How to create/get these Azure parameters?
### Tenant ID/Application ID and Key
Everything takes place in [Azure Active Directory](https://portal.azure.com/?l=en.en-us#blade/Microsoft_AAD_IAM)
* Navigate to [RegisteredApps](https://portal.azure.com/?l=en.en-us#blade/Microsoft_AAD_IAM/ActiveDirectoryMenuBlade/RegisteredApps)
* Click on "New application registration"
* Fill the form : Name="ligoj", Application type="Web app / API", Sign-on URL=ligoj URL, can be updated later
* "Create"
* Click on the create registration
* Copy the "Application ID"
* Click on "Keys" (right panel)
* In the "Passwords" panel, fill "Key Description" and "Duration", then "Save"
* Copy the one time displayed key value. 
* Navigate to [Properties](https://portal.azure.com/?l=en.en-us#blade/Microsoft_AAD_IAM/ActiveDirectoryMenuBlade/Properties)
* Copy the "Directory ID", used as "Tenant ID" by Ligoj

### Resource Group
Navigate to [Resource groups](https://portal.azure.com/?l=en.en-us#blade/HubsExtension/Resources/resourceType/Microsoft.Resources%2Fsubscriptions%2FresourceGroups)
Copy the resource group name
Grant the rights to "ligoj" account on the selected resource group

### Subscription
* Navigate to [Cost Management + Billing](https://portal.azure.com/?l=en.en-us#blade/Microsoft_Azure_Billing/SubscriptionsBlade)
* Get the subscription id from one of your enabled subscription

# Technical details
Used API is "Microsoft.Compute" (2017-03-30)
Authentication is OAuth2, no required CLI to be installed
Only RM type "VM" are supported, no "Classic".
Only the VM name is stored, not its identifier. So a deleted VM and then recreated with the same name is not lost by ligoj.
